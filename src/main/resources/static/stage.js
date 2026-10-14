(() => {
  for (const select of document.querySelectorAll('[data-stage]')) {
    const panel = select.closest('.stage-choice').querySelector('[data-boarded-choices]');
    const update = () => { panel.hidden = select.value !== 'onboard';panel.querySelector('select').required = select.value === 'onboard'; };
    select.addEventListener('change', update);update();
  }
})();
