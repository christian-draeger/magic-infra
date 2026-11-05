/* Illustrative workflow. All sample markup is static and ships with the page. */
(() => {
  'use strict';

  const root = document.documentElement;
  const select = (selector) => document.querySelector(selector);
  const steps = [...document.querySelectorAll('[data-step]')];
  const targets = [...document.querySelectorAll('[data-target]')];
  const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');
  const motionToggle = select('.motion-toggle');

  const samples = {
    discover: {
      file: 'build.gradle.kts / application.yaml',
      status: 'Evidence collected',
      html: `<span class="tok-comment">// build.gradle.kts</span>
dependencies {
  implementation(<span class="tok-string">"org.springframework.boot:spring-boot-starter-data-jpa"</span>)
  runtimeOnly(<span class="tok-string">"org.postgresql:postgresql"</span>)
}

<span class="tok-comment"># application.yaml</span>
spring:
  datasource:
    url: <span class="tok-string">\${DATABASE_URL}</span>

<span class="tok-good">✓ Detected:</span> relational database signals
<span class="tok-warn">? Your decision:</span> managed or external?`
    },
    declare: {
      file: 'infra.yaml',
      status: 'Ownership declared',
      html: `<span class="tok-comment"># I want this app to manage PostgreSQL.</span>
schemaVersion: <span class="tok-string">infra/v1alpha1</span>
application:
  id: shop
resources:
  - id: primary-db
    kind: <span class="tok-type">postgresql</span>
    management: <span class="tok-good">managed</span>
    deletionPolicy: <span class="tok-good">retain</span>
    connection:
      secretRef: shop-db-credentials
bindings:
  - serviceId: api
    resourceId: primary-db
    contract: spring.datasource`
    },
    docker: {
      file: 'compose.yaml · output summary',
      status: 'Docker Compose files ready to review',
      html: `<span class="tok-prompt">$</span> magic-infra plan --target docker

<span class="tok-good">✓</span> App: shop / api
<span class="tok-good">✓</span> PostgreSQL: primary-db · managed · retain
<span class="tok-good">✓</span> Target: Docker Compose on one host

<span class="tok-comment"># Planned output</span>
<span class="tok-string">compose.yaml</span>
  app service + PostgreSQL service
  named volume for PostgreSQL data
  binding via a local secret reference

<span class="tok-comment"># Review files before deploying with Docker Compose.</span>`
    },
    kubernetes: {
      file: 'k8s/ · output summary',
      status: 'Kubernetes manifests ready to review',
      html: `<span class="tok-prompt">$</span> magic-infra plan --target kubernetes

<span class="tok-good">✓</span> App: shop / api
<span class="tok-good">✓</span> PostgreSQL: primary-db · managed · retain
<span class="tok-good">✓</span> Target: Kubernetes / K3s

<span class="tok-comment"># Planned output</span>
<span class="tok-string">k8s/</span>
  app Deployment + Service
  CloudNativePG Cluster + persistent storage
  binding via a Kubernetes Secret reference

<span class="tok-warn">!</span> Requires the CloudNativePG operator and suitable storage.`
    }
  };

  let target = 'docker';
  const content = select('#code-content');
  function showStep(step) {
    const sample = samples[step === 'generate' ? target : step];
    for (const button of steps) {
      const active = button.dataset.step === step;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    }
    for (const button of targets) {
      button.setAttribute('aria-pressed', String(step === 'generate' && button.dataset.target === target));
    }
    select('#code-file-label').textContent = sample.file;
    select('#code-status').textContent = sample.status;
    content.innerHTML = sample.html;
    content.parentElement.scrollLeft = 0;
  }
  for (const button of steps) {
    button.addEventListener('click', () => showStep(button.dataset.step));
  }
  for (const button of targets) {
    button.addEventListener('click', () => {
      target = button.dataset.target;
      showStep('generate');
    });
  }
  showStep('discover');

  const nav = select('.menu-toggle');
  const menu = select('#nav-menu');
  function setMenu(open) {
    nav.setAttribute('aria-expanded', String(open));
    nav.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    menu.classList.toggle('open', open);
  }
  nav.addEventListener('click', () => setMenu(nav.getAttribute('aria-expanded') !== 'true'));
  menu.addEventListener('click', (event) => {
    if (event.target.closest('a')) setMenu(false);
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && menu.classList.contains('open')) {
      setMenu(false);
      nav.focus();
    }
  });

  function setMotionPaused(paused) {
    root.classList.toggle('motion-paused', paused);
    motionToggle.setAttribute('aria-pressed', String(paused));
  }
  function syncMotionPreference() {
    motionToggle.disabled = motionPreference.matches;
    setMotionPaused(motionPreference.matches);
  }
  syncMotionPreference();
  motionPreference.addEventListener('change', syncMotionPreference);
  motionToggle.addEventListener('click', () => setMotionPaused(!root.classList.contains('motion-paused')));

  root.classList.add('js-ready');
  const reveals = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window && !motionPreference.matches) {
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          observer.unobserve(entry.target);
        }
      }
    }, { threshold: 0.06 });
    for (const element of reveals) observer.observe(element);
  } else {
    for (const element of reveals) element.classList.add('is-visible');
  }

  const progress = select('#scroll-progress');
  let frame = 0;
  function updateProgress() {
    frame = 0;
    const range = root.scrollHeight - window.innerHeight;
    const fraction = range > 0 ? Math.max(0, Math.min(1, window.scrollY / range)) : 0;
    progress.style.transform = `scaleX(${fraction})`;
  }
  window.addEventListener('scroll', () => {
    if (!frame) frame = requestAnimationFrame(updateProgress);
  }, { passive: true });
  window.addEventListener('resize', updateProgress);
  updateProgress();
})();
