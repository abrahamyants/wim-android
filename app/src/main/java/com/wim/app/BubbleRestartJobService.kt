package com.wim.app

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.util.Log

class BubbleRestartJobService : JobService() {
    companion object {
        const val JOB_ID = 1001
    }

    override fun onStartJob(params: JobParameters): Boolean {
        try {
            startForegroundService(Intent(this, BubbleService::class.java).apply { action = "SHOW" })
            Log.i("WiM", "BubbleRestartJobService: startForegroundService dispatched")
        } catch (e: Exception) {
            Log.e("WiM", "BubbleRestartJobService: startForegroundService failed: ${e.javaClass.simpleName}")
        }
        return false
    }
    override fun onStopJob(params: JobParameters) = false
}
