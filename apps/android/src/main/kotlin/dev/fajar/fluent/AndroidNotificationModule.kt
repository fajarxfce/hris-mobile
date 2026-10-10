package dev.fajar.fluent

import android.content.Context
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.notifications.data.datasources.*
import org.koin.dsl.bind
import org.koin.dsl.module

fun androidNotificationModule(context: Context, environment: AppEnvironment) = module {
    single { AndroidNotificationPermissionSource(context) } bind NotificationPermissionSource::class
    single<NotificationDisplaySource> {
        AndroidNotificationDisplaySource(
            context,
            R.drawable.ic_notification,
            environment.linkScheme,
        )
    }
    single { AndroidPushTokenSource(context) } bind PushTokenSource::class
}
