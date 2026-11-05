import { createApp } from 'vue';
import App from './App.vue';
import './lib/theme.js';
// Guarda o aviso de instalação do navegador desde o início (ele chega uma vez só).
import './lib/install.js';
import './styles.css';

createApp(App).mount('#app');
