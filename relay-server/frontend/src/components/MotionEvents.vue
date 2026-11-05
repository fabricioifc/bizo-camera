<script setup>
import { computed, ref, watch } from 'vue';
import AppIcon from './AppIcon.vue';
import StatusMessage from './StatusMessage.vue';
import { api, ensureOk } from '../lib/api.js';
import { snapshotUrl } from '../lib/format.js';

// Aba Movimentos: filtro por tipo, linha do tempo (dias e horas), lista por dia e exclusão em lote.
const props = defineProps({
  deviceId: { type: String, required: true },
  events: { type: Array, required: true },
  loaded: Boolean,
});
const emit = defineEmits(['open', 'removed']);

const TYPES = [
  { value: 'all', label: 'Tudo' },
  { value: 'motion', label: 'Movimentos' },
  { value: 'video', label: 'Vídeos' },
  { value: 'photo', label: 'Fotos' },
];
const NOUNS = { all: ['registro', 'registros'], motion: ['movimento', 'movimentos'], video: ['vídeo', 'vídeos'], photo: ['foto', 'fotos'] };

const type = ref('all');
const day = ref('all'); // 'all' ou 'AAAA-MM-DD'
const hour = ref(null); // 0 a 23, só com um dia escolhido
const removing = ref(false);
const error = ref('');

const kindOf = (event) => (event.kind === 'photo' ? 'photo' : event.clip ? 'video' : 'motion');
const matchesType = (event) => {
  if (type.value === 'all') return true;
  if (type.value === 'motion') return event.kind !== 'photo';
  return kindOf(event) === type.value;
};

function dayKey(at) {
  const date = new Date(at);
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function dayLabel(key) {
  const today = dayKey(Date.now());
  const yesterday = dayKey(Date.now() - 86_400_000);
  if (key === today) return 'Hoje';
  if (key === yesterday) return 'Ontem';
  const [y, m, d] = key.split('-').map(Number);
  const label = new Date(y, m - 1, d).toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit', month: '2-digit' });
  return label.charAt(0).toUpperCase() + label.slice(1);
}

const timeOf = (at) => new Date(at).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });

// Eventos do tipo escolhido; os dias e as horas contam só esses.
const ofType = computed(() => props.events.filter(matchesType));

const days = computed(() => {
  const counts = new Map();
  for (const event of ofType.value) {
    const key = dayKey(event.at);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => (a[0] < b[0] ? 1 : -1)).map(([key, count]) => ({ key, count, label: dayLabel(key) }));
});

// Dia que deixou de ter eventos (excluídos ou outro filtro): volta para todos os dias.
watch(days, (list) => {
  if (day.value !== 'all' && !list.some((item) => item.key === day.value)) day.value = 'all';
});
watch(day, () => {
  hour.value = null;
});

const ofDay = computed(() => (day.value === 'all' ? ofType.value : ofType.value.filter((event) => dayKey(event.at) === day.value)));

// Linha do tempo do dia: quantos eventos de cada tipo em cada hora.
const hours = computed(() => {
  const buckets = Array.from({ length: 24 }, (_, h) => ({ hour: h, motion: 0, video: 0, photo: 0, total: 0 }));
  for (const event of ofDay.value) {
    const bucket = buckets[new Date(event.at).getHours()];
    bucket[kindOf(event)] += 1;
    bucket.total += 1;
  }
  return buckets;
});
const busiestHour = computed(() => Math.max(1, ...hours.value.map((bucket) => bucket.total)));

const shown = computed(() =>
  hour.value === null ? ofDay.value : ofDay.value.filter((event) => new Date(event.at).getHours() === hour.value),
);

// Lista agrupada por dia, do mais recente para o mais antigo.
const groups = computed(() => {
  const map = new Map();
  for (const event of shown.value) {
    const key = dayKey(event.at);
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(event);
  }
  return [...map.entries()].map(([key, list]) => ({ key, label: dayLabel(key), events: list }));
});

const noun = computed(() => {
  const [one, many] = NOUNS[type.value];
  return shown.value.length === 1 ? one : many;
});

function toggleHour(h) {
  hour.value = hour.value === h ? null : h;
}

function barStyle(bucket, kind) {
  return { height: `${(bucket[kind] / busiestHour.value) * 100}%` };
}

// Exclui tudo o que o filtro está mostrando.
async function removeShown() {
  const ids = shown.value.map((event) => event.id);
  if (!ids.length) return;
  const where = [
    day.value === 'all' ? 'de todos os dias' : `de ${dayLabel(day.value).toLowerCase()}`,
    hour.value === null ? '' : `entre ${hour.value}h e ${hour.value + 1}h`,
  ].filter(Boolean).join(', ');
  if (!window.confirm(`Excluir ${ids.length} ${noun.value} ${where}? Fotos e vídeos são apagados e não dá para desfazer.`)) return;
  removing.value = true;
  error.value = '';
  try {
    await ensureOk(await api(`/api/devices/${encodeURIComponent(props.deviceId)}/events`, {
      method: 'DELETE',
      body: JSON.stringify({ ids }),
    }));
    emit('removed', ids);
    hour.value = null;
  } catch (err) {
    error.value = err.message;
  } finally {
    removing.value = false;
  }
}
</script>

<template>
  <section class="timeline-view" aria-label="Movimentos e fotos">
    <!-- Filtros: tipo e exclusão do que está filtrado -->
    <div class="timeline-filters">
      <div class="segmented" role="group" aria-label="Tipo">
        <button
          v-for="option in TYPES"
          :key="option.value"
          type="button"
          :class="{ active: type === option.value }"
          :aria-pressed="type === option.value"
          @click="type = option.value"
        >{{ option.label }}</button>
      </div>
      <button
        type="button"
        class="btn btn-danger"
        :disabled="!shown.length || removing"
        @click="removeShown"
      >
        <AppIcon name="trash" :size="16" />
        {{ removing ? 'Excluindo…' : `Excluir ${shown.length} ${noun}` }}
      </button>
    </div>
    <StatusMessage :text="error" kind="error" />

    <!-- Dias com eventos -->
    <div v-if="days.length" class="day-chips" role="group" aria-label="Dia">
      <button type="button" class="day-chip" :class="{ active: day === 'all' }" @click="day = 'all'">
        Todos os dias <span>{{ ofType.length }}</span>
      </button>
      <button
        v-for="item in days"
        :key="item.key"
        type="button"
        class="day-chip"
        :class="{ active: day === item.key }"
        @click="day = item.key"
      >
        {{ item.label }} <span>{{ item.count }}</span>
      </button>
    </div>

    <!-- Linha do tempo do dia: atividade por hora; tocar numa hora filtra -->
    <div v-if="day !== 'all'" class="card hours">
      <div class="hours-head">
        <strong>{{ dayLabel(day) }}</strong>
        <span class="small muted">
          {{ hour === null ? 'Toque numa hora para filtrar.' : `Mostrando ${hour}h–${hour + 1}h.` }}
        </span>
        <button v-if="hour !== null" type="button" class="btn btn-ghost hours-clear" @click="hour = null">Ver o dia todo</button>
      </div>
      <div class="hours-bars">
        <button
          v-for="bucket in hours"
          :key="bucket.hour"
          type="button"
          class="hour"
          :class="{ active: hour === bucket.hour, empty: !bucket.total }"
          :disabled="!bucket.total"
          :title="`${bucket.hour}h: ${bucket.total} ${bucket.total === 1 ? 'registro' : 'registros'}`"
          :aria-label="`${bucket.hour} horas, ${bucket.total} registros`"
          @click="toggleHour(bucket.hour)"
        >
          <span class="hour-stack">
            <span class="hour-bar video" :style="barStyle(bucket, 'video')"></span>
            <span class="hour-bar motion" :style="barStyle(bucket, 'motion')"></span>
            <span class="hour-bar photo" :style="barStyle(bucket, 'photo')"></span>
          </span>
        </button>
      </div>
      <div class="hours-axis" aria-hidden="true">
        <span>0h</span><span>6h</span><span>12h</span><span>18h</span><span>24h</span>
      </div>
      <div class="hours-legend small muted">
        <span><i class="dot video"></i>Vídeo</span>
        <span><i class="dot motion"></i>Movimento</span>
        <span><i class="dot photo"></i>Foto</span>
      </div>
    </div>

    <!-- Lista por dia -->
    <div v-if="loaded && !events.length" class="card empty">
      <span class="empty-icon"><AppIcon name="motion" :size="28" /></span>
      <h2>Nenhum movimento registrado</h2>
      <p class="muted">Quando a câmera detectar movimento, a foto e o vídeo aparecem aqui. Use "Foto agora" para conferir a câmera na hora.</p>
    </div>
    <p v-else-if="loaded && !shown.length" class="card events-none muted">Nada com este filtro.</p>

    <div v-for="group in groups" :key="group.key" class="day-group">
      <h3 class="day-title">{{ group.label }} <span class="muted">· {{ group.events.length }}</span></h3>
      <ul class="events">
        <li v-for="event in group.events" :key="event.id" class="card event">
          <button type="button" class="event-open" :title="event.clip ? 'Ver vídeo' : 'Ver foto'" @click="$emit('open', event)">
            <img v-if="event.snapshot" :src="snapshotUrl(event.id)" alt="" loading="lazy">
            <span v-else class="no-photo"><AppIcon name="motion" /></span>
            <span v-if="event.clip" class="event-play" aria-hidden="true"><AppIcon name="play" :size="20" /></span>
          </button>
          <span class="event-time">
            <span class="event-kind" :class="kindOf(event)">
              {{ { photo: 'Foto', video: 'Vídeo', motion: 'Movimento' }[kindOf(event)] }}
            </span>
            {{ timeOf(event.at) }}
            <span v-if="event.clipPending" class="muted small">· gravando vídeo…</span>
          </span>
        </li>
      </ul>
    </div>
  </section>
</template>
