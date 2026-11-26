import { useEffect, useRef } from "react";
import { Compartment, EditorState, type Extension } from "@codemirror/state";
import {
  drawSelection,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers,
} from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import {
  bracketMatching,
  defaultHighlightStyle,
  foldGutter,
  foldKeymap,
  indentOnInput,
  syntaxHighlighting,
} from "@codemirror/language";
import { searchKeymap } from "@codemirror/search";
import { javascript } from "@codemirror/lang-javascript";
import { python } from "@codemirror/lang-python";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { java } from "@codemirror/lang-java";
import { rust } from "@codemirror/lang-rust";
import { sql } from "@codemirror/lang-sql";
import { xml } from "@codemirror/lang-xml";
import { yaml } from "@codemirror/lang-yaml";

function languageForFilename(filename: string): Extension[] {
  const ext = filename.includes(".") ? filename.split(".").pop()!.toLowerCase() : "";
  switch (ext) {
    case "js":
    case "mjs":
    case "cjs":
      return [javascript({ jsx: false, typescript: false })];
    case "jsx":
      return [javascript({ jsx: true, typescript: false })];
    case "ts":
    case "mts":
    case "cts":
      return [javascript({ jsx: false, typescript: true })];
    case "tsx":
      return [javascript({ jsx: true, typescript: true })];
    case "py":
      return [python()];
    case "json":
      return [json()];
    case "md":
    case "markdown":
      return [markdown()];
    case "html":
    case "htm":
      return [html()];
    case "css":
      return [css()];
    case "java":
    case "kt":
    case "kts":
      return [java()];
    case "rs":
      return [rust()];
    case "sql":
      return [sql()];
    case "xml":
    case "svg":
      return [xml()];
    case "yml":
    case "yaml":
      return [yaml()];
    default:
      return [];
  }
}

function buildEditorTheme(dark: boolean): Extension {
  return EditorView.theme(
    {
      "&": {
        height: "100%",
        backgroundColor: "var(--pi-surface)",
        color: "var(--pi-ink)",
        fontFamily: "var(--pi-font-mono)",
        fontSize: "12.5px",
      },
      ".cm-scroller": {
        overflow: "auto",
        fontFamily: "var(--pi-font-mono)",
        lineHeight: "1.6",
      },
      ".cm-content": {
        padding: "10px 0",
        caretColor: "var(--pi-accent)",
      },
      ".cm-gutters": {
        backgroundColor: "var(--pi-paper)",
        color: "var(--pi-ink-tertiary)",
        borderRight: "0.5px solid var(--pi-hairline)",
        userSelect: "none",
      },
      ".cm-activeLine": {
        backgroundColor: "var(--pi-hover)",
      },
      ".cm-activeLineGutter": {
        backgroundColor: "var(--pi-wash)",
        color: "var(--pi-ink)",
      },
      ".cm-selectionBackground, ::selection": {
        backgroundColor: "var(--pi-accent-soft) !important",
      },
      ".cm-cursor": {
        borderLeftColor: "var(--pi-accent)",
      },
      "&.cm-focused": {
        outline: "none",
      },
    },
    { dark },
  );
}

export function CodeEditorView({
  value,
  filename,
  dark,
  readOnly = false,
  onChange,
  onSave,
}: {
  value: string;
  filename: string;
  dark: boolean;
  readOnly?: boolean;
  onChange?: (val: string) => void;
  onSave?: () => void;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef<EditorView | null>(null);
  const langComp = useRef(new Compartment()).current;
  const themeComp = useRef(new Compartment()).current;
  const readOnlyComp = useRef(new Compartment()).current;
  const onChangeRef = useRef(onChange);
  const onSaveRef = useRef(onSave);
  onChangeRef.current = onChange;
  onSaveRef.current = onSave;

  useEffect(() => {
    const parent = containerRef.current;
    if (!parent) return undefined;

    const saveKeymap = keymap.of([
      {
        key: "Mod-s",
        run: () => {
          onSaveRef.current?.();
          return true;
        },
      },
    ]);

    const state = EditorState.create({
      doc: value,
      extensions: [
        lineNumbers(),
        highlightActiveLineGutter(),
        highlightActiveLine(),
        history(),
        foldGutter(),
        drawSelection(),
        indentOnInput(),
        bracketMatching(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        keymap.of([
          ...defaultKeymap,
          ...historyKeymap,
          ...foldKeymap,
          ...searchKeymap,
          indentWithTab,
        ]),
        saveKeymap,
        langComp.of(languageForFilename(filename)),
        themeComp.of(buildEditorTheme(dark)),
        readOnlyComp.of([EditorState.readOnly.of(readOnly), EditorView.editable.of(!readOnly)]),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            onChangeRef.current?.(update.state.doc.toString());
          }
        }),
      ],
    });

    const view = new EditorView({ state, parent });
    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
  }, [langComp, readOnlyComp, themeComp]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const current = view.state.doc.toString();
    if (current !== value) {
      view.dispatch({
        changes: { from: 0, to: current.length, insert: value },
      });
    }
  }, [value]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({
      effects: [
        langComp.reconfigure(languageForFilename(filename)),
        themeComp.reconfigure(buildEditorTheme(dark)),
        readOnlyComp.reconfigure([
          EditorState.readOnly.of(readOnly),
          EditorView.editable.of(!readOnly),
        ]),
      ],
    });
  }, [dark, filename, langComp, readOnly, readOnlyComp, themeComp]);

  return <div ref={containerRef} className="h-full w-full overflow-hidden" />;
}
