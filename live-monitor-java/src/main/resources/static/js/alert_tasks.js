(() => {
  const form = document.getElementById('deliveryTaskFilters');
  if (!form) return;
  let page = 1, total = 0;
  const error = document.getElementById('deliveryTaskError');
  async function request(url, options) {
    const response = await fetch(url, options);
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.message || body.detail || `请求失败 (${response.status})`);
    }
    return response.json();
  }
  async function load() {
    error.textContent = '';
    try {
      const params = new URLSearchParams();
      for (const [key, value] of new FormData(form)) if (value) params.set(key, value.replace('T', ' '));
      params.set('page', page);
      const data = await request(`/api/alert-tasks?${params}`);
      total = data.total;
      document.getElementById('deliveryTaskCounts').textContent = data.counts.map(c => `${c.delivery_status}: ${c.total}`).join(' | ')
        + (data.oldest_pending_at ? ` | 最早未完成: ${data.oldest_pending_at}` : '');
      document.getElementById('deliveryTaskPage').textContent = ` 第 ${page} 页，共 ${total} 条 `;
      document.getElementById('deliveryTaskPrev').disabled = page <= 1;
      document.getElementById('deliveryTaskNext').disabled = page * 50 >= total;
      const rows = document.getElementById('deliveryTaskRows');
      rows.replaceChildren();
      for (const task of data.items) {
        const row = document.createElement('tr');
        const next = ['PENDING', 'FAILED', 'SENDING'].includes(task.delivery_status) ? new Date(task.next_retry_at).toLocaleString() : '—';
        for (const value of [`${task.service_name || task.service_id} / ${task.channel_name || task.channel_id}`,
          `${task.event_id} / ${task.id}`, task.delivery_status, task.attempt_count, next, task.last_error || '—']) {
          const cell = document.createElement('td'); cell.textContent = String(value); row.append(cell);
        }
        const actions = document.createElement('td');
        const history = document.createElement('button'); history.type = 'button'; history.textContent = '发送历史';
        history.onclick = async () => {
          try {
            const attempts = await request(`/api/alert-tasks/${task.id}/attempts`);
            document.getElementById('deliveryTaskAttempts').textContent = JSON.stringify(attempts, null, 2);
          } catch (e) { error.textContent = e.message; }
        };
        actions.append(history);
        if (task.delivery_status === 'FINAL_FAILED') {
          const retry = document.createElement('button'); retry.type = 'button'; retry.textContent = '管理员重试';
          retry.onclick = async () => {
            retry.disabled = true;
            try { await request(`/api/alert-tasks/${task.id}/retry`, {method: 'POST'}); await load(); }
            catch (e) { error.textContent = e.message; retry.disabled = false; }
          };
          actions.append(retry);
        }
        row.append(actions); rows.append(row);
      }
    } catch (e) { error.textContent = e.message; }
  }
  form.addEventListener('submit', e => { e.preventDefault(); page = 1; load(); });
  document.getElementById('deliveryTaskPrev').onclick = () => { if (page > 1) { page--; load(); } };
  document.getElementById('deliveryTaskNext').onclick = () => { if (page * 50 < total) { page++; load(); } };
  load();
})();
