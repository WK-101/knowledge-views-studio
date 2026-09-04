package com.cairn.reader.domain.feed

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedModule {
    @Binds
    @Singleton
    abstract fun bindFeedParser(impl: XmlFeedParser): FeedParser
}
