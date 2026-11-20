package com.frederikberg.pai.contract

/** One field of a TypeScript interface body: its name, whether it is optional, and its type text. */
data class TsField(
    val name: String,
    val optional: Boolean,
    val type: String,
)

/**
 * The top-level fields of one interface, and the inline object types some of them carry. [fields]
 * are the interface's own; [bases] are the interfaces it extends.
 */
data class TsInterface(
    val name: String,
    val fields: Map<String, TsField>,
    val nested: Map<String, TsInterface>,
    val bases: List<String> = emptyList(),
)

/**
 * Reads the declarations the API type file consists of, as far as the contract check needs: the
 * field names of interfaces (inline object types included) and the literals of string unions. It is
 * not a TypeScript parser; a construct it does not recognise fails the check loudly.
 */
class TypeScriptDeclarations(
    source: String,
) {
    private val text = stripComments(source)

    /** Every `export interface`, by name. */
    val interfaces: Map<String, TsInterface> =
        INTERFACE_HEADER
            .findAll(text)
            .associate { match ->
                val name = match.groupValues[1]
                val open = match.range.last
                val close = matchingBrace(text, open)
                val bases =
                    match.groupValues[2]
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                name to parseInterface(name, text.substring(open + 1, close)).copy(bases = bases)
            }

    /** The fields of [name], those of the interfaces it extends included. */
    fun allFields(name: String): Set<String> {
        val declared = interfaces.getValue(name)
        return declared.bases.flatMap { allFields(it) }.toSet() + declared.fields.keys
    }

    /** Every `export type X = 'a' | 'b';`, by name, as the set of its literals. */
    val unions: Map<String, Set<String>> =
        TYPE_ALIAS
            .findAll(text)
            .mapNotNull { match ->
                val literals = literalsOf(match.groupValues[2])
                if (literals == null) null else match.groupValues[1] to literals
            }.toMap()

    /** The names of every declaration this file holds. */
    val declarationNames: Set<String> get() = interfaces.keys + unions.keys

    /** The literals of a field's type when it is a union of string literals; null for any other type. */
    fun literalsOfField(
        interfaceName: String,
        path: List<String>,
    ): Set<String>? {
        var current = interfaces.getValue(interfaceName)
        path.dropLast(1).forEach { current = current.nested.getValue(it) }
        return literalsOf(current.fields.getValue(path.last()).type)
    }

    private fun parseInterface(
        name: String,
        body: String,
    ): TsInterface {
        val fields = linkedMapOf<String, TsField>()
        val nested = linkedMapOf<String, TsInterface>()
        splitFields(body).forEach { raw ->
            if (raw.startsWith("[")) return@forEach // an index signature names no field
            val match = FIELD.matchEntire(raw) ?: error("unrecognised member in $name: ${raw.take(80)}")
            val field = TsField(match.groupValues[1], match.groupValues[2] == "?", match.groupValues[3].trim())
            fields[field.name] = field
            val inline = inlineObject(field.type)
            if (inline != null) nested[field.name] = parseInterface("$name.${field.name}", inline)
        }
        return TsInterface(name, fields, nested)
    }

    /** The body of a type that is an inline object, possibly `| null`; null for any other type. */
    private fun inlineObject(type: String): String? {
        val trimmed = type.trim()
        if (!trimmed.startsWith("{")) return null
        return trimmed.substring(1, matchingBrace(trimmed, 0))
    }

    private companion object {
        val INTERFACE_HEADER = Regex("""export interface (\w+)(?:\s+extends\s+([\w,\s]+))?\s*\{""")
        val TYPE_ALIAS = Regex("""export type (\w+)\s*=\s*([^;]*);""")
        val FIELD = Regex("""(\w+)(\??)\s*:\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
        val LITERAL = Regex("""'([^']*)'""")

        fun stripComments(source: String): String =
            source
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .lines()
                .joinToString("\n") { it.replace(Regex("""\s//.*$"""), "") }

        fun literalsOf(type: String): Set<String>? {
            val literals = LITERAL.findAll(type).map { it.groupValues[1] }.toSet()
            val rest = type.replace(LITERAL, "").replace("|", "").replace("null", "")
            return if (literals.isNotEmpty() && rest.isBlank()) literals else null
        }

        /** Index of the brace closing the one at [open]. */
        fun matchingBrace(
            text: String,
            open: Int,
        ): Int {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
            }
            error("unbalanced braces from index $open")
        }

        /** Splits an interface body into members at semicolons and commas outside any bracket. */
        fun splitFields(body: String): List<String> {
            val members = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            for (c in body) {
                when (c) {
                    '{', '[', '(', '<' -> depth++
                    '}', ']', ')', '>' -> depth--
                }
                if ((c == ';' || c == ',') && depth == 0) {
                    members += current.toString().trim()
                    current.clear()
                } else {
                    current.append(c)
                }
            }
            members += current.toString().trim()
            return members.filter { it.isNotEmpty() }
        }
    }
}
