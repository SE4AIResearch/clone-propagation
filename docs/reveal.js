// Scroll-triggered reveal animation, shared across all pages.
// Elements with class "reveal" fade up into view once they enter
// the viewport. Respects prefers-reduced-motion via CSS.
document.addEventListener('DOMContentLoaded', () => {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('in');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });

  document.querySelectorAll('.reveal').forEach((el) => observer.observe(el));
});

// Cursor-tracked glow, follows mouse across the hero for a
// premium, alive feel. Desktop only (CSS hides it on mobile).
document.addEventListener('DOMContentLoaded', () => {
  const hero = document.querySelector('.hero');
  if (hero && window.matchMedia('(min-width: 861px)').matches) {
    const glow = document.createElement('div');
    glow.className = 'cursor-glow';
    document.body.appendChild(glow);

    hero.addEventListener('mousemove', (e) => {
      glow.style.left = e.clientX + 'px';
      glow.style.top = e.clientY + 'px';
      glow.classList.add('active');
    });
    hero.addEventListener('mouseleave', () => {
      glow.classList.remove('active');
    });
  }

  // Trigger the staggered hero entrance sequence once fonts/layout
  // are ready, rather than on raw DOMContentLoaded, so nothing
  // pops in before styles have actually applied.
  requestAnimationFrame(() => {
    document.body.classList.add('enter-ready');
  });

  // Subtle 3D tilt on card hover, following cursor position within
  // the card rather than a fixed rotation.
  document.querySelectorAll('.card, .team-card').forEach((card) => {
    card.addEventListener('mousemove', (e) => {
      const rect = card.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const rotateX = ((y - rect.height / 2) / rect.height) * -6;
      const rotateY = ((x - rect.width / 2) / rect.width) * 6;
      card.style.transform = `perspective(800px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) translateY(-3px)`;
    });
    card.addEventListener('mouseleave', () => {
      card.style.transform = '';
    });
  });
});