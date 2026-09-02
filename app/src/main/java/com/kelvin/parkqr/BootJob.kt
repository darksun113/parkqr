package com.kelvin.parkqr

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * 持久化 JobScheduler 任务：重启后由**系统**重新调度，不经过开机广播。
 *
 * 车机 ROM 常把 BOOT_COMPLETED 掐掉、自启白名单也未必对第三方生效，
 * 但 JobScheduler 是系统服务，setPersisted(true) 的任务在重启后照常恢复，
 * 是绕开广播限制最可靠的一条路。
 *
 * 任务本身只做两件事：把后台定位服务拉起来、走一遍开机逻辑（含"在家不弹"判断）。
 */
class BootJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        runCatching {
            LocationService.start(this)
            // 开机 10 分钟内第一次跑到，算作一次开机事件
            if (BootLaunch.check(this, BootLaunch.JOB_WINDOW_MS)) OverlayService.start(this)
            schedule(this)
        }
        return false      // 同步做完，不需要 jobFinished
    }

    override fun onStopJob(params: JobParameters?) = false

    companion object {
        private const val JOB_ID = 1001
        private const val PERIOD_MS = 15 * 60_000L

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            runCatching {
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(ctx, BootJob::class.java))
                        .setPersisted(true)              // 重启后系统自动恢复
                        .setPeriodic(PERIOD_MS)
                        .setRequiresDeviceIdle(false)
                        .setRequiresCharging(false)
                        .build()
                )
            }
        }

        fun isScheduled(ctx: Context): Boolean = runCatching {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            js?.allPendingJobs?.any { it.id == JOB_ID } == true
        }.getOrDefault(false)
    }
}
