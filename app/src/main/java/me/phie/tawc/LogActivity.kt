package me.phie.tawc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.phie.tawc.util.AppLogger
import java.io.File

class LogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            LogScreen()
        }
    }
}

@Composable
fun LogScreen() {
    var logText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        AppLogger.getLogFile()?.let { file ->
            logText = if (file.exists()) {
                file.readText()
            } else {
                "Log file is empty"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TAWC Logs") },
                actions = {
                    IconButton(onClick = { 
                        AppLogger.clearLog()
                        logText = ""
                    }) {
                        Text("Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    AppLogger.getLogFile()?.let { file ->
                        logText = if (file.exists()) file.readText() else "Empty"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh Log")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = logText.ifEmpty { "No logs yet" },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
