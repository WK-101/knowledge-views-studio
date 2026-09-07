package com.obliviate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.obliviate.app.ui.ObliviateRoot
import com.obliviate.app.ui.theme.ObliviateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ObliviateTheme {
                ObliviateRoot()
            }
        }
    }
}
