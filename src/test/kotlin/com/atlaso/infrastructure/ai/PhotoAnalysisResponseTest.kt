package com.atlaso.infrastructure.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PhotoAnalysisResponseTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `unknown scene_type degrades to MISC without discarding other fields`() {
        // The vision model sometimes emits a scene_type outside the enum (e.g. "activity", which is
        // really a subject_type). This must NOT fail the whole response — otherwise keepsakeInterest,
        // primarySubject, aestheticScore etc. are lost to an all-defaults fallback.
        val json = """
            {
              "aesthetic_score": 0.82,
              "blur_score": 0.1,
              "faces_count": 2,
              "scene_type": "activity",
              "time_of_day": "day",
              "keepsake_interest": 0.9,
              "primary_subject": "surfing lesson"
            }
        """.trimIndent()

        val r = mapper.readValue<PhotoAnalysisResponse>(json)

        assertEquals(SceneType.MISC, r.sceneType)
        assertEquals(0.82, r.aestheticScore)
        assertEquals(0.9, r.keepsakeInterest)
        assertEquals("surfing lesson", r.primarySubject)
    }

    @Test
    fun `unknown time_of_day degrades to DAY`() {
        val json = """{"aesthetic_score":0.5,"blur_score":0.2,"faces_count":0,"scene_type":"food","time_of_day":"dusk"}"""
        val r = mapper.readValue<PhotoAnalysisResponse>(json)
        assertEquals(TimeOfDay.DAY, r.timeOfDay)
        assertEquals(SceneType.FOOD, r.sceneType)
    }

    @Test
    fun `known enum values still parse correctly`() {
        val json = """{"aesthetic_score":0.7,"blur_score":0.1,"faces_count":3,"scene_type":"people","time_of_day":"golden_hour"}"""
        val r = mapper.readValue<PhotoAnalysisResponse>(json)
        assertEquals(SceneType.PEOPLE, r.sceneType)
        assertEquals(TimeOfDay.GOLDEN_HOUR, r.timeOfDay)
    }
}
