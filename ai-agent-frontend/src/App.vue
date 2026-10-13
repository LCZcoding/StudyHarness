<template>
  <router-view v-slot="{ Component }">
    <transition name="page" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<script setup></script>

<style>
.page-enter-active,
.page-leave-active {
  transition: opacity 0.22s ease;
}
.page-enter-from,
.page-leave-to {
  opacity: 0;
}
</style>
