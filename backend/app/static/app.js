const menuButton = document.querySelector('[data-menu]');
const sidebar = document.querySelector('[data-sidebar]');
const overlay = document.querySelector('[data-overlay]');

function closeMenu() {
  sidebar?.classList.remove('open');
  overlay?.classList.remove('show');
}

menuButton?.addEventListener('click', () => {
  sidebar?.classList.toggle('open');
  overlay?.classList.toggle('show');
});
overlay?.addEventListener('click', closeMenu);
document.querySelectorAll('[data-confirm]').forEach((form) => {
  form.addEventListener('submit', (event) => {
    if (!window.confirm(form.dataset.confirm || 'ادامه می‌دهید؟')) event.preventDefault();
  });
});

