/**
 * Visitor Counter — frontend logic
 *
 * Expects window.API_BASE_URL to be set by config.js (generated at deploy time).
 * Example: window.API_BASE_URL = "https://abc123.execute-api.us-east-1.amazonaws.com/prod";
 */

(function () {
  'use strict';

  const API_BASE = (window.API_BASE_URL || '').replace(/\/$/, '');
  const ENDPOINT = API_BASE + '/visit';

  const countEl  = document.getElementById('visitor-count');
  const btn      = document.getElementById('visit-btn');
  const statusEl = document.getElementById('status-msg');
  const warning  = document.getElementById('config-warning');

  // ── Initialise ─────────────────────────────────────────────────────────────

  if (!API_BASE) {
    warning.style.display = 'block';
    btn.disabled = true;
    setStatus('API endpoint not configured — see config.js', 'error');
    return;
  }

  fetchCount();

  btn.addEventListener('click', registerVisit);

  // ── API calls ──────────────────────────────────────────────────────────────

  async function fetchCount() {
    setStatus('Loading…', 'info');
    try {
      const data = await apiFetch('GET');
      updateCounter(data.visitorCount, false);
      setStatus('');
    } catch (err) {
      countEl.textContent = '?';
      setStatus('Could not load visitor count: ' + err.message, 'error');
    }
  }

  async function registerVisit() {
    btn.disabled = true;
    setStatus('Registering your visit…', 'info');

    try {
      const data = await apiFetch('POST');
      updateCounter(data.visitorCount, true);
      setStatus('Visit recorded — thank you! 🎉', 'success');
    } catch (err) {
      setStatus('Failed to register visit: ' + err.message, 'error');
    } finally {
      // Re-enable after a short cooldown to prevent double-clicks
      setTimeout(() => { btn.disabled = false; }, 2000);
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  async function apiFetch(method) {
    const res = await fetch(ENDPOINT, {
      method,
      headers: { 'Content-Type': 'application/json' },
    });

    if (!res.ok) {
      let msg = `HTTP ${res.status}`;
      try {
        const body = await res.json();
        if (body.error) msg += ': ' + body.error;
      } catch (_) { /* ignore parse error */ }
      throw new Error(msg);
    }

    return res.json();
  }

  function updateCounter(value, animate) {
    countEl.textContent = Number(value).toLocaleString();

    if (animate) {
      countEl.classList.remove('bump');
      // Force reflow so the transition re-fires
      void countEl.offsetWidth;
      countEl.classList.add('bump');
      setTimeout(() => countEl.classList.remove('bump'), 400);
    }
  }

  function setStatus(msg, type) {
    statusEl.textContent = msg;
    statusEl.className   = type || '';
  }

}());
