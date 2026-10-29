package ui.a2ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ui.Alignment
import ui.Arrangement
import ui.Box
import ui.Color
import ui.Column
import ui.Interaction
import ui.Modifier
import ui.Row
import ui.dp
import ui.fillMaxWidth
import ui.log
import ui.padding
import ui.size
import ui.weight
import ui.material.Button
import ui.material.ButtonStyle
import ui.material.Card
import ui.material.CheckboxRow
import ui.material.ColorScheme
import ui.material.FilterChip
import ui.material.FlowRow
import ui.material.HorizontalDivider
import ui.material.IconButton
import ui.material.LocalTextStyle
import ui.material.MaterialTheme
import ui.material.Slider
import ui.material.Surface
import ui.material.TabRow
import ui.material.Text
import ui.material.TextFieldDisplay
import ui.material.Typography
import ui.material.VerticalDivider
import ui.material.container
import ui.material.darkColorScheme

/**
 * Renders [surface] with the basic catalog of A2UI v0.9, as Material 3
 * components; the surface's theme may set the seed color (primaryColor),
 * otherwise [colors].
 */
@Composable
fun A2uiView(client: A2uiClient, surface: Surface, modifier: Modifier = Modifier, colors: ColorScheme? = null) {
    val seed = surface.theme?.get("primaryColor").string?.let { hex(it) }
    val scheme = remember(seed, colors) { if (seed != null) darkColorScheme(seed) else colors ?: darkColorScheme() }
    MaterialTheme(scheme) {
        CompositionLocalProvider(LocalTextStyle provides Typography.bodyLarge) {
            Box(modifier) { Node(Ctx(client, surface), "root", "/", Modifier.fillMaxWidth(), 0) }
        }
    }
}

private fun hex(s: String): Color? {
    val h = s.removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xff000000 or v)
        8 -> Color(((v and 0xff) shl 24) or (v ushr 8))  // #RRGGBBAA
        else -> null
    }
}

private class Ctx(val client: A2uiClient, val surface: Surface)

/** The children of a container: (component id, data scope) for a list of ids or a template over a data array. */
private fun children(ctx: Ctx, v: Values, value: Json?, scope: String): List<Pair<String, String>> {
    value.arr?.let { ids -> return ids.mapNotNull { it.string?.let { id -> id to scope } } }
    val o = value.obj ?: return emptyList()
    o["explicitList"].arr?.let { ids -> return ids.mapNotNull { it.string?.let { id -> id to scope } } }
    val t = o["template"].obj ?: o
    val path = (t["path"] ?: t["dataBinding"]).string ?: return emptyList()
    val template = t["componentId"].string ?: return emptyList()
    val at = resolvePath(path, scope)
    val n = ctx.surface.get(at).let { it.arr?.size ?: it.obj?.fields?.size ?: 0 }
    val keys = ctx.surface.get(at).obj?.fields?.keys?.toList()
    return List(n) { i -> template to "$at/${keys?.get(i) ?: i}" }
}

@Composable
private fun Node(ctx: Ctx, id: String, scope: String, modifier: Modifier, depth: Int) {
    val cs = MaterialTheme.colors
    val c = ctx.surface.components[id]
    if (c == null || depth > 24) {  // not arrived yet (or a cycle)
        Box(modifier.size(24.dp).container(cs.surfaceContainerHighest, 6.dp))
        return
    }
    val v = Values(ctx.surface, scope)
    when (val type = c["component"].string) {
        "Text" -> TextNode(v.string(c["text"]) ?: "", (c["variant"] ?: c["usageHint"]).string ?: "body", modifier)
        "Icon" -> Box(modifier) { A2uiIcon(v.string(c["name"]) ?: "", cs.primary) }
        "Row", "Column", "List" -> {
            val horizontal = type == "Row" || (type == "List" && c["direction"].string == "horizontal")
            Linear(ctx, v, c, scope, horizontal, modifier, depth)
        }
        "Card" -> Card(modifier) {
            c["child"].string?.let { Node(ctx, it, scope, Modifier.fillMaxWidth(), depth + 1) }
                ?: Linear(ctx, v, c, scope, false, Modifier.fillMaxWidth(), depth)
        }
        "Divider" -> if (c["axis"].string == "vertical") VerticalDivider(modifier) else HorizontalDivider(modifier)
        "Button" -> ButtonNode(ctx, v, c, id, scope, modifier, depth)
        "CheckBox" -> CheckBoxNode(v, c, modifier)
        "ChoicePicker", "MultipleChoice" -> ChoiceNode(v, c, modifier)
        "Slider" -> SliderNode(v, c, modifier)
        "TextField", "DateTimeInput" -> TextFieldDisplay(v.string(c["label"]), v.string(c["value"] ?: c["text"]), modifier, "-")
        "Tabs" -> TabsNode(ctx, v, c, scope, modifier, depth)
        "Modal" -> ModalNode(ctx, c, scope, modifier, depth)
        "Image", "Video", "AudioPlayer" -> MediaNode(type, v, c, modifier)
        else -> Text("[$type]", modifier, Typography.bodyMedium, cs.onSurfaceVariant)
    }
}

/** A2UI's text variants in Material's type scale; body text takes the style around it (a button's label). */
@Composable
private fun TextNode(text: String, variant: String, modifier: Modifier) {
    val cs = MaterialTheme.colors
    val clean = remember(text) { plain(text) }
    when (variant) {
        "h1" -> Text(clean, modifier, Typography.headlineMedium)
        "h2" -> Text(clean, modifier, Typography.headlineSmall)
        "h3" -> Text(clean, modifier, Typography.titleLarge)
        "h4" -> Text(clean, modifier, Typography.titleMedium)
        "h5" -> Text(clean, modifier, Typography.titleSmall)
        "caption" -> Text(clean, modifier, Typography.bodyMedium, cs.onSurfaceVariant)
        else -> Text(clean, modifier)
    }
}

@Composable
private fun Linear(ctx: Ctx, v: Values, c: Json.Obj, scope: String, horizontal: Boolean, modifier: Modifier, depth: Int) {
    val kids = children(ctx, v, c["children"], scope)
    val justify = c["justify"].string ?: c["distribution"].string
    val align = c["align"].string ?: c["alignment"].string ?: if (horizontal) "center" else "stretch"
    val arrangement = when (justify) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "spaceBetween" -> Arrangement.SpaceBetween
        "spaceAround", "spaceEvenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(if (horizontal) 12.dp else if (depth == 0) 16.dp else 8.dp)  // looser between a screen's parts
    }
    fun childModifier(cid: String): Modifier {
        val w = ctx.surface.components[cid]?.get("weight").double?.toFloat()
        var m: Modifier = Modifier
        if (w != null && w > 0f) m = m.weight(w)
        if (!horizontal && align == "stretch") m = m.fillMaxWidth()
        return m
    }
    if (horizontal) {
        val va = when (align) {
            "start" -> Alignment.Top
            "end" -> Alignment.Bottom
            else -> Alignment.CenterVertically
        }
        Row(modifier, arrangement, va) { for ((cid, s) in kids) Node(ctx, cid, s, childModifier(cid), depth + 1) }
    } else {
        val ha = when (align) {
            "center" -> Alignment.CenterHorizontally
            "end" -> Alignment.End
            else -> Alignment.Start
        }
        Column(modifier, arrangement, ha) { for ((cid, s) in kids) Node(ctx, cid, s, childModifier(cid), depth + 1) }
    }
}

/** primary: a filled button; borderless: a text button; the others: tonal. */
@Composable
private fun ButtonNode(ctx: Ctx, v: Values, c: Json.Obj, id: String, scope: String, modifier: Modifier, depth: Int) {
    val style = when ((c["variant"] ?: c["primary"]).string) {
        "primary", "true" -> ButtonStyle.Filled
        "borderless" -> ButtonStyle.Text
        else -> ButtonStyle.Tonal
    }
    val interaction = remember { Interaction() }
    Button({ act(ctx, v, c["action"], id) }, modifier.reveal(interaction), style, v.checksPass(c["checks"]), interaction = interaction) {
        // A label is centered; a Row or Column of content spans the button.
        c["child"].string?.let { child ->
            val container = ctx.surface.components[child]?.get("component").string in setOf("Row", "Column", "List")
            Node(ctx, child, scope, if (container) Modifier.fillMaxWidth() else Modifier, depth + 1)
        }
            ?: Text(v.string(c["label"] ?: c["text"]) ?: "OK")
    }
}

/** A Button's action: an event for the server, or a function run here. */
private fun act(ctx: Ctx, v: Values, action: Json?, id: String) {
    val a = action.obj ?: return
    val event = a["event"].obj ?: a.takeIf { it["name"] != null }
    if (event != null) {
        val context = LinkedHashMap<String, Json>()
        when (val raw = event["context"]) {
            is Json.Obj -> for ((k, x) in raw.fields) v.resolve(x)?.let { context[k] = it }
            is Json.Arr -> for (e in raw.items) {  // v0.8: [{"key":..., "value":...}]
                val k = e.obj?.get("key").string ?: continue
                v.resolve(e.obj?.get("value"))?.let { context[k] = it }
            }
            else -> {}
        }
        ctx.client.act(ctx.surface, id, event["name"].string ?: "action", Json.Obj(context))
        return
    }
    a["functionCall"].obj?.let { f -> log("a2ui: local function ${f["call"].string} (not available here)") }
}

@Composable
private fun CheckBoxNode(v: Values, c: Json.Obj, modifier: Modifier) {
    var local by remember { mutableStateOf<Boolean?>(null) }
    val pointer = v.pointer(c["value"])
    val checked = local ?: v.bool(c["value"]) ?: false
    val interaction = remember { Interaction() }
    CheckboxRow(checked, {
        if (pointer != null) v.surface.set(pointer, Json.Bool(!checked)) else local = !checked
    }, v.string(c["label"]) ?: "", modifier.reveal(interaction), interaction = interaction)
}

/** Filter chips, one of them selected (mutuallyExclusive) or any number. */
@Composable
private fun ChoiceNode(v: Values, c: Json.Obj, modifier: Modifier) {
    val cs = MaterialTheme.colors
    val options = c["options"].arr.orEmpty().mapNotNull { o ->
        when (o) {
            is Json.Obj -> {
                val label = v.string(o["label"]) ?: o["value"].string ?: return@mapNotNull null
                label to (o["value"].string ?: label)
            }
            else -> o.string?.let { it to it }
        }
    }
    val single = c["variant"].string == "mutuallyExclusive" || c["maxAllowedSelections"].double == 1.0
    val pointer = v.pointer(c["value"] ?: c["selections"])
    var local by remember { mutableStateOf<List<String>?>(null) }
    val selected = local ?: v.resolve(c["value"] ?: c["selections"]).let { r -> r.arr?.mapNotNull { it.string } ?: listOfNotNull(r.string) }
    fun pick(value: String) {
        val next = if (single) listOf(value) else if (value in selected) selected - value else selected + value
        if (pointer != null) v.surface.set(pointer, Json.Arr(next.map { Json.Str(it) })) else local = next
    }
    Column(modifier, Arrangement.spacedBy(8.dp)) {
        (v.string(c["label"] ?: c["description"]))?.let { Text(it, style = Typography.bodyMedium, color = cs.onSurfaceVariant) }
        FlowRow(Modifier.fillMaxWidth()) {
            for ((label, value) in options) {
                val interaction = remember(value) { Interaction() }
                FilterChip(value in selected, { pick(value) }, label, Modifier.reveal(interaction), interaction)
            }
        }
    }
}

/** Material's slider, set with the - and + buttons beside it (the keys can't drag). */
@Composable
private fun SliderNode(v: Values, c: Json.Obj, modifier: Modifier) {
    val cs = MaterialTheme.colors
    val min = v.double(c["min"] ?: c["minValue"]) ?: 0.0
    val max = (v.double(c["max"] ?: c["maxValue"]) ?: 100.0).coerceAtLeast(min + 1e-9)
    val step = v.double(c["step"]) ?: if (max - min <= 20) 1.0 else (max - min) / 20
    val pointer = v.pointer(c["value"])
    var local by remember { mutableStateOf<Double?>(null) }
    val value = (local ?: v.double(c["value"]) ?: min).coerceIn(min, max)
    fun set(x: Double) {
        val n = x.coerceIn(min, max)
        if (pointer != null) v.surface.set(pointer, Json.Num(n)) else local = n
    }
    Column(modifier, Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(v.string(c["label"]) ?: "", Modifier.weight(1f), Typography.bodyMedium, cs.onSurfaceVariant)
            Text(formatNumber(value, null, true), style = Typography.labelLarge, color = cs.primary)
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            val less = remember { Interaction() }
            val more = remember { Interaction() }
            IconButton("remove", { set(value - step) }, Modifier.reveal(less), enabled = value > min, interaction = less)
            Slider(((value - min) / (max - min)).toFloat(), Modifier.weight(1f))
            IconButton("add", { set(value + step) }, Modifier.reveal(more), enabled = value < max, interaction = more)
        }
    }
}

@Composable
private fun TabsNode(ctx: Ctx, v: Values, c: Json.Obj, scope: String, modifier: Modifier, depth: Int) {
    // [{"title", "child"}] (tabs or tabItems), or "titles" beside "children".
    val tabs = (c["tabs"] ?: c["tabItems"]).arr?.mapNotNull { t ->
        val o = t.obj ?: return@mapNotNull null
        (v.string(o["title"]) ?: "") to (o["child"].string ?: return@mapNotNull null)
    } ?: run {
        val titles = c["titles"].arr.orEmpty().map { v.string(it) ?: "" }
        c["children"].arr.orEmpty().mapIndexedNotNull { i, ch -> ch.string?.let { (titles.getOrNull(i) ?: "${i + 1}") to it } }
    }
    var selected by remember { mutableIntStateOf(0) }
    Column(modifier, Arrangement.spacedBy(16.dp)) {
        TabRow(tabs.map { it.first }, selected, { selected = it })
        tabs.getOrNull(selected)?.let { Node(ctx, it.second, scope, Modifier.fillMaxWidth(), depth + 1) }
    }
}

/** The trigger opens it in place, as a dialog-like surface (28 dp corners) with Close. */
@Composable
private fun ModalNode(ctx: Ctx, c: Json.Obj, scope: String, modifier: Modifier, depth: Int) {
    val cs = MaterialTheme.colors
    var open by remember { mutableStateOf(false) }
    val trigger = (c["trigger"] ?: c["entryPointChild"]).string
    val content = (c["content"] ?: c["contentChild"]).string
    Column(modifier, Arrangement.spacedBy(12.dp)) {
        // The trigger opens it; a Button trigger shows its own child.
        val label = trigger?.let { ctx.surface.components[it] }?.let { t -> if (t["component"].string == "Button") t["child"].string else trigger }
        val opener = remember { Interaction() }
        Button({ open = !open }, Modifier.reveal(opener), ButtonStyle.Tonal, interaction = opener) {
            if (label != null) Node(ctx, label, scope, Modifier, depth + 1) else Text("Open")
        }
        if (open && content != null) {
            Surface(Modifier.fillMaxWidth(), cs.surfaceContainerHigh, cs.onSurface, 28.dp) {
                Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(16.dp)) {
                    Node(ctx, content, scope, Modifier.fillMaxWidth(), depth + 1)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        val closer = remember { Interaction() }
                        Button({ open = false }, Modifier.reveal(closer), ButtonStyle.Text, interaction = closer) { Text("Close") }
                    }
                }
            }
        }
    }
}

/** Images, video and audio come by URL, which this renderer doesn't fetch: a placeholder with the file's name. */
@Composable
private fun MediaNode(type: String, v: Values, c: Json.Obj, modifier: Modifier) {
    val cs = MaterialTheme.colors
    val url = v.string(c["url"] ?: c["src"]) ?: ""
    val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { type.lowercase() }
    val icon = if (type == "Image") "image" else "play_arrow"
    when (c["variant"].string) {
        "icon", "avatar" -> Box(modifier.size(40.dp).container(cs.surfaceContainerHighest, if (c["variant"].string == "avatar") 20.dp else 8.dp), contentAlignment = Alignment.Center) {
            A2uiIcon(icon, cs.onSurfaceVariant)
        }
        else -> Row(modifier.fillMaxWidth().container(cs.surfaceContainerHighest, 12.dp).padding(16.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            A2uiIcon(icon, cs.onSurfaceVariant, 32)
            Text(name, Modifier.weight(1f), Typography.bodyMedium, cs.onSurfaceVariant, maxLines = 2)
        }
    }
}
