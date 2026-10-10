import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Test data shared with the other modules' tests (src/testFixtures: testCall, testContact).
    `java-test-fixtures`
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Pure-Java vCard parser/writer. Its HTML (jsoup, freemarker) and jCard (jackson) extras are never used,
    // so they are excluded to keep the APK small and free of unused code.
    api(libs.ezvcard) {
        exclude(group = "org.jsoup")
        exclude(group = "org.freemarker")
        exclude(group = "com.fasterxml.jackson.core")
    }
    // Offline number parsing (E.164 with the SIM country as hint, numbers inside free text). Pure Java, no network.
    implementation(libs.libphonenumber)
    // The offline geocoder's prefix maps (AreaNames reads its packed area names with them).
    implementation(libs.phone.geocoder)
    // QR decoding from pictures (pure Java, offline). The app already ships it for making QR codes.
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}

/*
 * libphonenumber's data, packed into two files (read by app.parley.common.phone.PhoneData) instead of about 1,100 tiny
 * class-path files, each of which costs a ZIP header in the APK. The app leaves the originals out (app/build.gradle.kts).
 * - phone_metadata.bin: number metadata and short numbers, one compressed stream of (name, length, bytes) entries.
 * - area_names.bin: the area names of the languages Parley ships (GeoLanguages.SHIPPED) without China and Australia
 *   (GeoLanguages.COUNTRIES_WITHOUT_AREAS; keep both lists in step), each file compressed on its own behind an index.
 * Sorted names and a fixed compression level keep the output byte-identical from build to build.
 */
val phoneData: Configuration by configurations.creating { isTransitive = false }
dependencies {
    phoneData(libs.libphonenumber)
    phoneData(libs.phone.geocoder)
}
val packPhoneData by tasks.registering {
    group = "parley"
    description = "Packs libphonenumber's metadata and area names into two resources."
    val jars: FileCollection = phoneData
    val out = layout.buildDirectory.dir("generated/phoneData")
    val areaLanguages = setOf("en", "de", "es", "fr", "pt", "ar")
    val countriesWithoutAreas = setOf("86", "61")
    inputs.files(jars).withPropertyName("jars")
    inputs.property("areaLanguages", areaLanguages)
    inputs.property("countriesWithoutAreas", countriesWithoutAreas)
    outputs.dir(out)
    doLast {
        val metadata = sortedMapOf<String, ByteArray>()
        val areas = sortedMapOf<String, ByteArray>()
        val areaFile = Regex("""com/google/i18n/phonenumbers/geocoding/data/(\d+)_(\w+)""")
        jars.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { e ->
                    val n = e.name
                    val area = areaFile.matchEntire(n)
                    if (n.startsWith("com/google/i18n/phonenumbers/data/PhoneNumberMetadataProto_") ||
                        n.startsWith("com/google/i18n/phonenumbers/data/ShortNumberMetadataProto_")
                    ) {
                        metadata["/$n"] = zip.getInputStream(e).use { it.readBytes() }
                    } else if (area != null && area.groupValues[2] in areaLanguages && area.groupValues[1] !in countriesWithoutAreas) {
                        areas[n.substringAfterLast('/')] = zip.getInputStream(e).use { it.readBytes() }
                    }
                }
            }
        }
        check(metadata.isNotEmpty() && areas.isNotEmpty()) { "libphonenumber's data files were not found in ${jars.files}" }
        fun deflate(bytes: ByteArray): ByteArray {
            val buffer = ByteArrayOutputStream()
            DeflaterOutputStream(buffer, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(bytes) }
            return buffer.toByteArray()
        }
        val dir = out.get().asFile.resolve("app/parley/common/phone").apply { deleteRecursively(); mkdirs() }
        val solid = ByteArrayOutputStream()
        DataOutputStream(solid).use { d ->
            metadata.forEach { (name, bytes) -> d.writeUTF(name); d.writeInt(bytes.size); d.write(bytes) }
            d.writeUTF("")
        }
        dir.resolve("phone_metadata.bin").writeBytes(deflate(solid.toByteArray()))
        val packed = areas.mapValues { deflate(it.value) }
        DataOutputStream(dir.resolve("area_names.bin").outputStream().buffered()).use { d ->
            d.writeInt(packed.size)
            packed.forEach { (name, bytes) -> d.writeUTF(name); d.writeInt(bytes.size) }
            packed.values.forEach(d::write)
        }
        logger.lifecycle("packPhoneData: ${metadata.size} metadata files, ${areas.size} area-name files")
    }
}
sourceSets["main"].resources.srcDir(packPhoneData)

// Offline spam-list pack builder (see app.parley.common.spam.PackTool), e.g.
// ./gradlew :core:common:buildSpamPack --args="--ftc dnc.csv --out ftc.parleylist --id gov.ftc.dnc --name 'FTC reported calls'"
tasks.register<JavaExec>("buildSpamPack") {
    group = "parley"
    description = "Builds a .parleylist spam-list pack from public data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.parley.common.spam.PackTool")
    workingDir = rootProject.projectDir
}

// RuleTemplateTest validates the templates shipped in the app's assets.
tasks.named<Test>("test") {
    inputs.dir(rootProject.file("app/src/main/assets/templates")).withPropertyName("templateAssets")
}
