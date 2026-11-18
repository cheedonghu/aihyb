/* Progressive enhancement only; published content remains readable without JavaScript. */
(() => {
  function enhanceRankings() {
    document.querySelectorAll('[data-ranking-catalog]').forEach(root => {
      if (root.dataset.enhanced) return;
      const panels = [...root.querySelectorAll('[data-ranking-board]')];
      const tabs = [...root.querySelectorAll('[data-ranking-tab]')];
      if (!panels.length || !tabs.length) return;
      function choose(panel, updateHash = false) {
        panels.forEach(item => { item.hidden = item !== panel; });
        tabs.forEach(tab => {
          if (tab.hash === '#' + panel.id) tab.setAttribute('aria-current', 'true');
          else tab.removeAttribute('aria-current');
        });
        if (updateHash) {
          try { history.replaceState(history.state, '', '#' + panel.id); }
          catch { /* Local previews may not permit history replacement. */ }
        }
      }
      function followHash() {
        const target = panels.find(panel => '#' + panel.id === location.hash);
        if (!target) return false;
        choose(target);
        return true;
      }
      tabs.forEach(tab => tab.addEventListener('click', event => {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        const panel = panels.find(item => '#' + item.id === tab.hash);
        if (!panel) return;
        event.preventDefault();
        choose(panel, true);
      }));
      window.addEventListener('hashchange', followHash);
      root.dataset.enhanced = 'true';
      if (!followHash()) choose(panels[0]);
    });
  }
  function enhanceHardware() {
    document.querySelectorAll('[data-hardware-budget]').forEach(select => {
      if (select.dataset.enhanced) return;
      const board = select.closest('[data-ranking-board]');
      const rows = [...board.querySelectorAll('[data-ranking-row]')];
      const count = board.querySelector('[data-ranking-count]');
      const empty = board.querySelector('[data-hardware-empty]');
      const chart = board.querySelector('[data-ranking-chart]');
      function filter() {
        const budget = Number(select.value);
        let visible = 0;
        rows.forEach(row => {
          let matches = !budget;
          row.querySelectorAll('[data-hardware-record]').forEach(record => {
            const measured = Number(record.dataset.measuredMb);
            record.hidden = Boolean(budget) && !(measured > 0 && measured <= budget);
            if (!record.hidden) matches = true;
          });
          row.hidden = !matches;
          if (matches) visible++;
        });
        count.textContent = budget ? `符合条件 ${visible} / ${rows.length} 个模型` : `${rows.length} 个模型`;
        chart.hidden = visible === 0;
        empty.hidden = visible !== 0;
      }
      select.addEventListener('change', filter);
      select.dataset.enhanced = 'true';
      filter();
      select.closest('[data-hardware-control]').hidden = false;
    });
  }
  function enhance() {
    enhanceRankings();
    enhanceHardware();
    document.querySelectorAll('.aihyb-native pre').forEach(pre => {
      if (pre.querySelector('.aihyb-copy')) return;
      const code = pre.querySelector('code');
      if (!code) return;
      const button = document.createElement('button');
      button.type = 'button'; button.className = 'aihyb-copy'; button.textContent = '复制内容';
      button.addEventListener('click', async () => {
        try {
          if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
          await navigator.clipboard.writeText(code.textContent);
          button.textContent = '已复制';
        } catch {
          const range = document.createRange(); range.selectNodeContents(code);
          const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
          button.textContent = '已选中，请复制';
        }
        setTimeout(() => { button.textContent = '复制内容'; }, 2200);
      });
      pre.append(button);
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', enhance, {once:true});
  else enhance();
})();
