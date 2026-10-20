function print() {
  console.warn("background.js loaded");
  setTimeout(print, 500)
}

setTimeout(print, 500)
