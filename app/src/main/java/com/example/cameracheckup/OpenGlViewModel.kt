package com.example.cameracheckup

import androidx.lifecycle.ViewModel

class OpenGlViewModel : ViewModel() {

    var supportsGL3: Boolean = false

    var glVersion: String = ""

    var glExtensions: String = ""


}
