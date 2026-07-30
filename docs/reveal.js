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