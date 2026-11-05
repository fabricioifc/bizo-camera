import { ref, watch } from 'vue';

// Tema da página: "system" segue o aparelho; "light" e "dark" ficam salvos neste navegador.
const KEY = 'theme';
const THEME_COLORS = { light: '#f5f6f8', dark: '#0e1014' };
const media = window.matchMedia('(prefers-color-scheme: dark)');

function readSaved() {
  try {
    const saved = localStorage.getItem(KEY);
    return saved === 'light' || saved === 'dark' ? saved : 'system';
  } catch {
    return 'system';
  }
}

export const theme = ref(readSaved());

function apply() {
  const root = document.documentElement;
  // Sem data-theme, o CSS segue prefers-color-scheme sozinho.
  if (theme.value === 'system') delete root.dataset.theme;
  else root.dataset.theme = theme.value;
  const resolved = theme.value === 'system' ? (media.matches ? 'dark' : 'light') : theme.value;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[resolved]);
}

watch(theme, (value) => {
  try {
    if (value === 'system') localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, value);
  } catch {
    // Sem armazenamento (aba anônima, por exemplo): o tema vale só até fechar a página.
  }
  apply();
});

media.addEventListener('change', apply);
apply();
