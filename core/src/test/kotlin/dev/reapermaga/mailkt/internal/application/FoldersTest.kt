package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FoldersTest {
    @Test fun `lists folders resolves special use and reports unknown folders`() = runBlocking<Unit> {
        val rig = Rig.open()
        rig.server.folder("INBOX"); rig.server.folder("Sent"); rig.server.folder("Misc")
        assertEquals(3, rig.folders.list().size)
        assertEquals("Sent", rig.folders.special(SpecialUse.SENT)?.path?.value)
        assertEquals("INBOX", rig.folders.special(SpecialUse.INBOX)?.path?.value)
        assertNull(rig.folders.special(SpecialUse.TRASH))
        assertFailsWith<MailException.FolderNotFound> { rig.messages.page(folderSel("Nope")) }
        rig.close()
    }

    @Test fun `append returns the new location`() = runBlocking<Unit> {
        val rig = Rig.open()
        rig.server.folder("Drafts")
        val loc = rig.folders.append(FolderPath("Drafts"), rig.outbox.newDraft().copy(to = listOf(MailParticipant(MailAddress("b@example.com")))))
        assertNotNull(loc)
        assertEquals(1, rig.server.folder("Drafts").messages.size)
        rig.close()
    }
}
