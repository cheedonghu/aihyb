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
  function enhance() {
    enhanceRankings();
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
