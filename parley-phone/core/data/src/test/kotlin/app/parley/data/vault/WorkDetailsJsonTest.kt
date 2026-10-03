package app.parley.data.vault

import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import app.parley.data.ContactDraftJson
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A private contact (and an editor draft) keeps the whole work row: department, office and job description. */
@RunWith(RobolectricTestRunner::class)
class WorkDetailsJsonTest {
    private val d = ContactDetails(
        given = "Ada", company = "Acme", title = "Engineer", department = "Research", officeLocation = "Room 4", jobDescription = "Builds rockets",
    )

    private fun work(x: ContactDetails) = listOf(x.company, x.title, x.department, x.officeLocation, x.jobDescription)

    @Test fun privateContactsKeepTheWholeWorkRow() {
        assertEquals(work(d), work(ContactDetailsJson.decode(ContactDetailsJson.encode(d))))
    }

    @Test fun olderPrivateEntriesStillEncodeTheSame() {
        // Entries without a department encode as before, so their stored fingerprints don't change.
        val plain = d.copy(department = "", officeLocation = "", jobDescription = "")
        assertEquals(false, ContactDetailsJson.encode(plain).contains("dept"))
    }

    @Test fun editorDraftsKeepTheDepartment() {
        assertEquals(work(d), work(ContactDraftJson.decode(ContactDraftJson.encode(d))))
    }
}
