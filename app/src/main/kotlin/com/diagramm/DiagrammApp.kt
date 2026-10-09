package com.diagramm

import android.app.Application

class DiagrammApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
