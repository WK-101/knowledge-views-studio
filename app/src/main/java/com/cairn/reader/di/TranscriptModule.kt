package com.cairn.reader.di

import com.cairn.reader.domain.transcript.SpeechToTextEngine
import com.cairn.reader.domain.transcript.WhisperEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the on-device speech-to-text engine. Swapping in a different engine (or a real whisper.cpp
 *  JNI build) is a one-line change here; nothing else in the app references the concrete type. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TranscriptModule {
    @Binds
    @Singleton
    abstract fun bindSpeechToTextEngine(impl: WhisperEngine): SpeechToTextEngine
}
