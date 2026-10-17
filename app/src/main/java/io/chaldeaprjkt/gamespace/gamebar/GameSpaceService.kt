/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.gamebar

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log

import dagger.hilt.android.AndroidEntryPoint

import io.chaldeaprjkt.gamespace.data.GameOptimizationManager

import javax.inject.Inject

@AndroidEntryPoint(Service::class)
class GameSpaceService : Hilt_GameSpaceService() {

    private val TAG = "GameSpaceService"

    @Inject
    lateinit var gameOptimization: GameOptimizationManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_BACKGROUND)
        Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_BACKGROUND)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "game_start" -> {
                intent.getStringExtra("package_name")?.let {
                    Log.d(TAG, "Game started: $it")
                    SessionService.start(applicationContext, it)
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                    Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_SYSTEM)
                    Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_SYSTEM)
                    gameOptimization.optimizeGameLaunch(it)
                }
            }
            "game_stop" -> {
                Log.d(TAG, "Game left")
                SessionService.stop(applicationContext)
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_BACKGROUND)
                Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_BACKGROUND)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
