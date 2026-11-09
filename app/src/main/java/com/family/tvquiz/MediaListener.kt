package com.family.tvquiz

import android.service.notification.NotificationListenerService

/**
 * 只用来取得"通知使用权"。有了这个权限，守门服务才能读取
 * 其他应用的媒体会话（是否正在播放）。不读取、不保存任何通知内容。
 */
class MediaListener : NotificationListenerService()
