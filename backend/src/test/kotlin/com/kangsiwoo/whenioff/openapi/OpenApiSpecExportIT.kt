package com.kangsiwoo.whenioff.openapi

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.util.Separators
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path

/**
 * springdoc가 만든 OpenAPI 문서를 desktop이 타입을 생성하는 `desktop/openapi.json`으로 내보낸다 (#56).
 *
 * `./gradlew exportOpenApi`가 `wio.openapi.out` 시스템 프로퍼티로 출력 경로를 넘길 때만 돈다.
 * 일반 `test`/`check`에서는 건너뛴다(skipped) — 테스트가 레포의 다른 디렉터리를 고쳐 쓰지 않게.
 * 키를 정렬하고 들여쓰기를 고정해서, 스펙이 바뀌지 않으면 파일도 바이트 단위로 같다. CI는 이 태스크를
 * 돌린 뒤 `git diff --exit-code`로 커밋된 스펙이 낡았는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecExportIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
    ) {
        @Test
        fun `export openapi spec`() {
            val out = System.getProperty(OUT_PROPERTY)
            assumeTrue(!out.isNullOrBlank(), "$OUT_PROPERTY not set — run ./gradlew exportOpenApi")

            val body =
                mockMvc
                    .perform(get("/v3/api-docs"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString

            val path = Path.of(out!!)
            Files.createDirectories(path.parent)
            Files.writeString(path, normalize(body) + "\n")
        }

        private fun normalize(json: String): String {
            val mapper = ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            val tree = mapper.readValue(json, Map::class.java)
            val indenter = DefaultIndenter("  ", "\n")
            val printer =
                DefaultPrettyPrinter()
                    .withSeparators(
                        Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER),
                    ).apply {
                        indentObjectsWith(indenter)
                        indentArraysWith(indenter)
                    }
            return mapper.writer(printer).writeValueAsString(tree)
        }

        companion object {
            const val OUT_PROPERTY = "wio.openapi.out"
        }
    }
