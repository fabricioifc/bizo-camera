<script setup>
import AlertsCard from '../components/AlertsCard.vue';
import PageHeader from '../components/PageHeader.vue';
import ThemeToggle from '../components/ThemeToggle.vue';
</script>

<template>
  <PageHeader title="Configurações">
    <template #subtitle>Valem só para este aparelho</template>
  </PageHeader>

  <div class="page-narrow">
    <AlertsCard />

    <section class="card" aria-labelledby="theme-title">
      <div class="card-header">
        <div>
          <h2 id="theme-title">Aparência</h2>
          <p class="small muted">Automático segue o tema do celular ou do computador.</p>
        </div>
      </div>
      <div class="card-body">
        <ThemeToggle />
      </div>
    </section>
  </div>
</template>
