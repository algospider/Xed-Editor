package com.rk.ai.nativeagent.ui.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown

@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (DiffParser.isDiff(text.trimStart())) {
        DiffContent(diffText = text, modifier = modifier)
        return
    }

    Column(modifier = modifier) {
        Markdown(text = text)
        Spacer(Modifier.height(6.dp))
    }
}
