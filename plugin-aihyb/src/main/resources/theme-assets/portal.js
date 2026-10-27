/* Progressive enhancement only; published content remains readable without JavaScript. */
(() => {
  function enhance() {
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
