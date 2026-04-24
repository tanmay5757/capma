package com.example.andriod_project

import android.app.Application
import com.example.andriod_project.capma.runtime.DomainKnowledge

class CapmaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DomainKnowledge.load(this)
    }
}
