import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  base: "./",
  plugins: [react(), tailwindcss()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          react: ["react", "react-dom"],
          radix: [
            "@radix-ui/react-alert-dialog",
            "@radix-ui/react-dialog",
            "@radix-ui/react-dropdown-menu",
            "@radix-ui/react-popover",
            "@radix-ui/react-select",
            "@radix-ui/react-slider",
            "@radix-ui/react-switch",
            "@radix-ui/react-tabs",
            "@radix-ui/react-tooltip",
          ],
          markdown: ["react-markdown", "remark-gfm"],
          highlight: ["highlight.js"],
          codemirror: [
            "@codemirror/state",
            "@codemirror/view",
            "@codemirror/commands",
            "@codemirror/language",
            "@codemirror/search",
            "@codemirror/lang-javascript",
            "@codemirror/lang-python",
            "@codemirror/lang-json",
            "@codemirror/lang-markdown",
            "@codemirror/lang-html",
            "@codemirror/lang-css",
            "@codemirror/lang-java",
            "@codemirror/lang-rust",
            "@codemirror/lang-sql",
            "@codemirror/lang-xml",
            "@codemirror/lang-yaml",
          ],
          xterm: ["@xterm/xterm", "@xterm/addon-fit"],
          docs: ["mammoth", "dompurify"],
        },
      },
    },
  },
});
