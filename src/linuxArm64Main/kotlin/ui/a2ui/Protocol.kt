package ui.a2ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.gmtime_r
import platform.posix.strftime
import platform.posix.time
import platform.posix.tm
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar

/** The A2UI version this renderer speaks; v0.9.1 messages are taken too. */
const val A2UI_VERSION = "v0.9"
const val BASIC_CATALOG = "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

/**
 * One surface: a tree of components (a flat map, by id, with "root" at the
 * top) and its data model, both snapshot state, so the screen follows every
 * message.
 */
class Surface(val id: String, val catalogId: String, val theme: Json.Obj?, val sendDataModel: Boolean) {
    val components = mutableStateMapOf<String, Json.Obj>()
    var data: Json by mutableStateOf(Json.Obj(emptyMap()))
        internal set

    /** The value at a JSON Pointer ("/a/0/b"), or null. */
    fun get(pointer: String): Json? {
        var v: Json? = data
        for (key in segments(pointer)) {
            v = when (v) {
                is Json.Obj -> v[key]
                is Json.Arr -> key.toIntOrNull()?.let { v.items.getOrNull(it) }
                else -> null
            }
        }
        return v
    }

    /** Sets (or with null removes) the value at [pointer], making objects on the way. */
    fun set(pointer: String, value: Json?) {
        data = put(data, segments(pointer), value) ?: Json.Obj(emptyMap())
    }

    private fun put(node: Json?, path: List<String>, value: Json?): Json? {
        if (path.isEmpty()) return value
        val key = path[0]
        if (node is Json.Arr) {
            val idx = key.toIntOrNull()
            if (idx != null && idx in 0..node.items.size) {
                val items = node.items.toMutableList()
                val v = put(node.items.getOrNull(idx), path.drop(1), value)
                if (idx == items.size) { if (v != null) items += v } else if (v == null) items.removeAt(idx) else items[idx] = v
                return Json.Arr(items)
            }
        }
        val fields = LinkedHashMap((node as? Json.Obj)?.fields ?: emptyMap())
        val v = put(fields[key], path.drop(1), value)
        if (v == null) fields.remove(key) else fields[key] = v
        return Json.Obj(fields)
    }
}

/** A JSON Pointer's keys, "~1" and "~0" unescaped. */
internal fun segments(pointer: String): List<String> =
    pointer.split('/').filter { it.isNotEmpty() }.map { it.replace("~1", "/").replace("~0", "~") }

/** [path] inside [scope] (a template item's pointer) unless it is absolute. */
internal fun resolvePath(path: String, scope: String): String =
    if (path.startsWith("/")) path else scope.trimEnd('/') + "/" + path

/** What a component sends when it is used (a Button's event). */
class Action(val name: String, val surfaceId: String, val sourceComponentId: String, val context: Json.Obj, val timestamp: String) {
    /** The client-to-server message, with the data model when the surface asks for it. */
    fun message(dataModel: Json?): Json.Obj = Json.obj(
        "version" to Json.str(A2UI_VERSION),
        "action" to Json.obj(
            "name" to Json.str(name),
            "surfaceId" to Json.str(surfaceId),
            "sourceComponentId" to Json.str(sourceComponentId),
            "timestamp" to Json.str(timestamp),
            "context" to context,
        ),
        "metadata" to dataModel?.let { Json.obj("a2uiClientDataModel" to Json.obj("surfaceId" to Json.str(surfaceId), "value" to it)) },
    )
}

/**
 * The client side of A2UI: takes server-to-client messages (createSurface,
 * updateComponents, updateDataModel, deleteSurface) and keeps the surfaces
 * they describe; [onAction] gets what the user does on them.
 */
class A2uiClient(val onAction: (Action, Surface) -> Unit = { _, _ -> }) {
    val surfaces = mutableStateListOf<Surface>()

    fun surface(id: String) = surfaces.firstOrNull { it.id == id }

    /** Applies one message. Returns an error message for the server, or null. */
    fun handle(message: Json): Json.Obj? {
        val m = message.obj ?: return error("", "/", "a message must be an object")
        val version = m["version"].string
        if (version != null && !version.startsWith("v0.9")) return error("", "/version", "unsupported version $version")
        m["createSurface"].obj?.let { c ->
            val id = c["surfaceId"].string ?: return error("", "/createSurface/surfaceId", "missing surfaceId")
            if (surface(id) != null) return error(id, "/createSurface/surfaceId", "surface $id exists")
            surfaces += Surface(id, c["catalogId"].string ?: BASIC_CATALOG, c["theme"].obj, c["sendDataModel"].bool == true)
            return null
        }
        m["updateComponents"].obj?.let { u ->
            val id = u["surfaceId"].string ?: return error("", "/updateComponents/surfaceId", "missing surfaceId")
            val s = surface(id) ?: return error(id, "/updateComponents/surfaceId", "no surface $id")
            val list = u["components"].arr ?: return error(id, "/updateComponents/components", "missing components")
            list.forEachIndexed { i, c ->
                val o = c.obj
                val cid = o?.get("id").string
                if (o == null || cid == null || o["component"].string == null) return error(id, "/updateComponents/components/$i", "a component needs id and component")
                s.components[cid] = o
            }
            return null
        }
        m["updateDataModel"].obj?.let { u ->
            val id = u["surfaceId"].string ?: return error("", "/updateDataModel/surfaceId", "missing surfaceId")
            val s = surface(id) ?: return error(id, "/updateDataModel/surfaceId", "no surface $id")
            s.set(u["path"].string ?: "/", u["value"])
            return null
        }
        m["deleteSurface"].obj?.let { d ->
            val id = d["surfaceId"].string ?: return error("", "/deleteSurface/surfaceId", "missing surfaceId")
            surfaces.removeAll { it.id == id }
            return null
        }
        return error("", "/", "unknown message")
    }

    internal fun act(surface: Surface, componentId: String, name: String, context: Json.Obj) =
        onAction(Action(name, surface.id, componentId, context, isoNow()), surface)

    companion object {
        fun error(surfaceId: String, path: String, text: String) = Json.obj(
            "version" to Json.str(A2UI_VERSION),
            "error" to Json.obj("code" to Json.str("VALIDATION_FAILED"), "surfaceId" to Json.str(surfaceId),
                "path" to Json.str(path), "message" to Json.str(text)),
        )
    }
}

/** Now in UTC, ISO 8601. */
internal fun isoNow(): String = memScoped {
    val t = alloc<platform.posix.time_tVar>()
    time(t.ptr)
    val parts = alloc<tm>()
    gmtime_r(t.ptr, parts.ptr)
    val buf = allocArray<ByteVar>(32)
    strftime(buf, 32u, "%Y-%m-%dT%H:%M:%SZ", parts.ptr)
    buf.toKString()
}
