from pathlib import Path

PATH = Path("app/src/main/java/com/abelcrvg/newsrss/MainActivity.kt")


def matching_brace(text: str, start: int) -> int:
    depth = 0
    quote = None
    escaped = False
    line_comment = False
    block_comment = False
    i = start
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ""
        if line_comment:
            if c == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            if c == "*" and n == "/":
                block_comment = False
                i += 2
                continue
            i += 1
            continue
        if quote:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == quote:
                quote = None
            i += 1
            continue
        if c == "/" and n == "/":
            line_comment = True
            i += 2
            continue
        if c == "/" and n == "*":
            block_comment = True
            i += 2
            continue
        if c in ('"', "'"):
            quote = c
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise RuntimeError("Could not find matching brace")


text = PATH.read_text(encoding="utf-8")

if "val showScrollToTop = remember" not in text:
    marker = "val listState = rememberLazyListState()"
    if marker not in text:
        raise RuntimeError("LazyListState marker not found")
    text = text.replace(
        marker,
        marker + '\n    val showScrollToTop = remember { derivedStateOf { listState.firstVisibleItemIndex > 2 || listState.firstVisibleItemScrollOffset > 400 } }',
        1,
    )

if "Modifier.align(Alignment.BottomEnd)" not in text:
    column = "        Column(Modifier.fillMaxSize().padding(padding)) {"
    if column not in text:
        raise RuntimeError("Main content Column not found")

    text = text.replace(
        column,
        "        Box(Modifier.fillMaxSize()) {\n" + column,
        1,
    )

    column_open = text.index("{", text.index(column))
    column_close = matching_brace(text, column_open)
    fab = '''\n            if (tab == 0 && showScrollToTop.value) {\n                FloatingActionButton(\n                    onClick = { scope.launch { listState.animateScrollToItem(0) } },\n                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 88.dp)\n                ) { Text("↑") }\n            }'''
    text = text[:column_close + 1] + fab + text[column_close + 1:]

    scaffold_start = text.index("    Scaffold(")
    body_marker = text.index(") { padding ->", scaffold_start)
    lambda_open = text.index("{", body_marker)
    lambda_close = matching_brace(text, lambda_open)
    text = text[:lambda_close] + "\n        }" + text[lambda_close:]

PATH.write_text(text, encoding="utf-8")
