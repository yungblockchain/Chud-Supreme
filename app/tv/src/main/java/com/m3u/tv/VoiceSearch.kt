package com.m3u.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.Locale

/* -------------------------------------------------------------------------------------------------
 * Voice search. On a TV with speech recognition (Google TV, most Android TV boxes) the mic
 * button opens the system's listener and the words land in the search box. A Fire TV keeps its
 * microphone for Alexa, so there the button points to the phone page, which listens on the phone.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun VoiceSearchButton(
    onWords: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val available = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val usePhone = stringResource(R.string.dial_voice_use_phone)
    val prompt = stringResource(R.string.dial_voice_prompt)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!words.isNullOrEmpty()) onWords(words)
    }
    TvIconActionButton(
        icon = Icons.Rounded.Mic,
        contentDescription = stringResource(R.string.dial_voice_search),
        focusRequester = focusRequester,
        modifier = modifier,
        onClick = {
            if (!available) {
                Toast.makeText(context, usePhone, Toast.LENGTH_LONG).show()
                return@TvIconActionButton
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, usePhone, Toast.LENGTH_LONG).show()
            }
        },
    )
}
