package com.family.tvquiz.parent

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import org.json.JSONObject
import java.time.Duration
import java.util.Base64

/**
 * 用 Claude 识别照片并出题。只发送家长选中的照片和学科/册次/单元信息，不发送答题记录。
 * 输出用 JSON Schema 约束（structured outputs），保证能直接解析。
 */
object Claude {
    private const val MODEL = "claude-opus-5-5"

    data class Meta(val book: String, val subject: String, val unit: String, val material: String, val note: String)

    data class Candidate(
        val stem: String, val options: List<String>, val answer: String, val explain: String,
        val lesson: String, val kind: String, val uncertain: Boolean, val uncertainReason: String,
    )

    data class Result(val questions: List<Candidate>, val notes: String)

    private val SYSTEM = """
你在帮家长给小学生做电视答题题库。孩子用遥控器答题，所以每道题必须是选择题：2 到 4 个选项，选项简短，正确答案必须原样出现在选项里。

根据家长拍的照片出题：
- 课本照片：提取课文、生字词、例题和知识点，出同步练习题。
- 作业或试卷照片：先原样提取原题（kind=original），再为每道原题出 1 到 2 道考同一知识点的变体题（kind=variant）。原题是填空或计算时，把它改写成选择题，干扰项要是孩子常见的错误答案。
- 题目难度要符合照片所示的年级和单元，用孩子能看懂的简短中文（英语题可以用英文）。

照片看不清、手写答案无法辨认、或你不确定正确答案时：把 uncertain 设为 true，并在 uncertain_reason 里说明原因。不要猜一个答案假装确定。
不要出需要看图才能回答的题（电视上不显示图片）。每次最多出 20 道题。
在 notes 里简要说明照片里有什么内容、哪些部分没有出题以及原因。
""".trimIndent()

    private fun schema(): JsonOutputFormat.Schema {
        val str = mapOf("type" to "string")
        val question = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("stem", "options", "answer", "explain", "lesson", "kind", "uncertain", "uncertain_reason"),
            "properties" to mapOf(
                "stem" to mapOf("type" to "string", "description" to "题干"),
                "options" to mapOf("type" to "array", "items" to str, "description" to "2 到 4 个选项"),
                "answer" to mapOf("type" to "string", "description" to "正确答案，必须与某个选项完全相同"),
                "explain" to mapOf("type" to "string", "description" to "一句话解析"),
                "lesson" to mapOf("type" to "string", "description" to "课题或知识点"),
                "kind" to mapOf("type" to "string", "enum" to listOf("original", "variant", "practice")),
                "uncertain" to mapOf("type" to "boolean"),
                "uncertain_reason" to str,
            ),
        )
        return JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("required", JsonValue.from(listOf("questions", "notes")))
            .putAdditionalProperty("properties", JsonValue.from(mapOf(
                "questions" to mapOf("type" to "array", "items" to question),
                "notes" to str,
            )))
            .build()
    }

    /** 调用 Claude。会阻塞，必须在后台线程调用。 */
    fun extract(apiKey: String, photos: List<ByteArray>, meta: Meta): Result {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMinutes(5))
            .build()
        try {
            val blocks = ArrayList<ContentBlockParam>()
            for (jpg in photos) {
                blocks += ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .data(Base64.getEncoder().encodeToString(jpg))
                        .build())
                    .build())
            }
            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(
                "材料类型：${meta.material}\n册次：${meta.book}\n学科：${meta.subject}\n单元：${meta.unit}" +
                    (if (meta.note.isNotBlank()) "\n家长补充：${meta.note}" else "") +
                    "\n请根据以上 ${photos.size} 张照片出题。"
            ).build())

            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder()
                    .effort(OutputConfig.Effort.HIGH)
                    .format(JsonOutputFormat.builder().schema(schema()).build())
                    .build())
                .addUserMessageOfContentBlockParams(blocks)
                // 被安全分类器拒绝时，由服务端自动换模型重试
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()

            val msg = client.messages().create(params)
            if (msg.stopReason().orElse(null) == StopReason.REFUSAL) {
                val why = msg.stopDetails().flatMap { it.explanation() }.orElse("")
                throw ApiException("Claude 拒绝处理这些照片。$why")
            }
            if (msg.stopReason().orElse(null) == StopReason.MAX_TOKENS) {
                throw ApiException("题目太多，输出被截断了。请少放几张照片再试。")
            }
            val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            return parse(JSONObject(text))
        } finally {
            client.close()
        }
    }

    private fun parse(o: JSONObject): Result {
        val arr = o.getJSONArray("questions")
        val list = List(arr.length()) { i ->
            val q = arr.getJSONObject(i)
            val opts = q.getJSONArray("options")
            Candidate(
                stem = q.getString("stem"),
                options = List(opts.length()) { opts.getString(it).trim() },
                answer = q.getString("answer").trim(),
                explain = q.optString("explain"),
                lesson = q.optString("lesson"),
                kind = q.optString("kind"),
                uncertain = q.optBoolean("uncertain"),
                uncertainReason = q.optString("uncertain_reason"),
            )
        }
        return Result(list, o.optString("notes"))
    }
}
