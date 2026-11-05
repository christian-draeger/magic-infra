package example

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/notes")
@ConditionalOnProperty("spring.datasource.url")
class NotesController(private val jdbc: JdbcClient) {
    @PostMapping("/{id}")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable id: String, @RequestBody body: String) {
        try {
            jdbc.sql("INSERT INTO notes (id, body) VALUES (:id, :body)")
                .param("id", id).param("body", body).update()
        } catch (_: DuplicateKeyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT)
        }
    }

    @GetMapping("/{id}")
    fun read(@PathVariable id: String): String =
        jdbc.sql("SELECT body FROM notes WHERE id = :id").param("id", id)
            .query(String::class.java).optional().orElseThrow(::missingNote)

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun update(@PathVariable id: String, @RequestBody body: String) {
        val changed = jdbc.sql("UPDATE notes SET body = :body WHERE id = :id")
            .param("id", id).param("body", body).update()
        if (changed == 0) throw missingNote()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) {
        val changed = jdbc.sql("DELETE FROM notes WHERE id = :id").param("id", id).update()
        if (changed == 0) throw missingNote()
    }

    private fun missingNote() = ResponseStatusException(HttpStatus.NOT_FOUND)
}
