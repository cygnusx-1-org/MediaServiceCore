package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaGroupOptionsTest {
    private val sections = mapOf(
        MediaGroup.TYPE_GAMING to MediaServiceData.CONTENT_SHORTS_GAMING,
        MediaGroup.TYPE_MUSIC to MediaServiceData.CONTENT_SHORTS_MUSIC,
        MediaGroup.TYPE_SPORTS to MediaServiceData.CONTENT_SHORTS_SPORTS,
        MediaGroup.TYPE_LIVE to MediaServiceData.CONTENT_SHORTS_LIVE,
        MediaGroup.TYPE_MY_VIDEOS to MediaServiceData.CONTENT_SHORTS_MY_VIDEOS,
        MediaGroup.TYPE_NEWS to MediaServiceData.CONTENT_SHORTS_NEWS
    )

    @After
    fun tearDown() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_SHORTS_ALL, false)
    }

    @Test
    fun testThatEachSectionHidesOnlyItsOwnShorts() {
        val data = MediaServiceData.instance()

        for ((hiddenType, content) in sections) {
            data.setContentHidden(MediaServiceData.CONTENT_SHORTS_ALL, false)
            data.setContentHidden(content, true)

            for (type in sections.keys) {
                assertEquals("Section $type, shorts hidden from $hiddenType", type == hiddenType, MediaGroupOptions.create(type).removeShorts)
            }
        }
    }

    @Test
    fun testThatHidingEverywhereHidesFromEverySection() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_SHORTS_ALL, true)

        for (type in sections.keys) {
            assertTrue("Section $type", MediaGroupOptions.create(type).removeShorts)
        }
    }
}
