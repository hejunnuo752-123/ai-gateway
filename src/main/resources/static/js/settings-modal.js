/**
 * 设置弹窗 —— 三个管理页共用的独立组件。
 *
 * 刻意用原生 JS 而不是各页面的 Vue 实例：
 * 三个页面的 setup() 结构各不相同，塞进去要改三份 return 语句，
 * 抽成独立组件后各页面只需 <script src> 一行 + 侧边栏一个图标。
 *
 * 对外只暴露 window.openSettingsModal()。
 */
(function () {
    'use strict';

    const CSS = `
    #settingsModal .settings-tabs {
        display: flex; gap: 4px; padding: 0 16px;
        border-bottom: 1px solid rgba(0,240,255,0.12);
    }
    #settingsModal .settings-tab {
        padding: 10px 18px; font-size: 0.85rem; cursor: pointer;
        color: #94a3b8; border-bottom: 2px solid transparent;
        transition: all .18s; user-select: none;
    }
    #settingsModal .settings-tab:hover { color: #e2e8f0; }
    #settingsModal .settings-tab.active {
        color: #00f0ff; border-bottom-color: #00f0ff;
        text-shadow: 0 0 12px rgba(0,240,255,0.4);
    }
    #settingsModal .bg-grid {
        display: grid; grid-template-columns: repeat(3, 1fr);
        gap: 12px; max-height: 340px; overflow-y: auto; padding: 2px;
    }
    #settingsModal .bg-cell {
        position: relative; aspect-ratio: 16/10; border-radius: 10px;
        overflow: hidden; cursor: pointer;
        border: 2px solid rgba(0,240,255,0.15);
        transition: all .18s; background: rgba(255,255,255,0.03);
    }
    #settingsModal .bg-cell:hover {
        border-color: rgba(0,240,255,0.5);
        box-shadow: 0 0 14px rgba(0,240,255,0.18);
    }
    #settingsModal .bg-cell.current {
        border-color: #00f0ff;
        box-shadow: 0 0 18px rgba(0,240,255,0.35);
    }
    #settingsModal .bg-cell img {
        width: 100%; height: 100%; object-fit: cover; display: block;
    }
    #settingsModal .bg-cell .bg-tag {
        position: absolute; left: 6px; bottom: 6px;
        font-size: 0.62rem; padding: 1px 6px; border-radius: 4px;
        background: rgba(5,7,10,0.78); color: #94a3b8;
        backdrop-filter: blur(4px);
    }
    #settingsModal .bg-cell .bg-check {
        position: absolute; right: 6px; top: 6px;
        width: 20px; height: 20px; border-radius: 50%;
        background: #00f0ff; color: #05070a;
        display: flex; align-items: center; justify-content: center;
        font-size: 0.7rem;
    }
    #settingsModal .bg-cell .bg-del {
        position: absolute; right: 6px; bottom: 6px;
        width: 22px; height: 22px; border-radius: 6px;
        background: rgba(5,7,10,0.8); color: #ff5252;
        border: 1px solid rgba(255,82,82,0.35);
        display: none; align-items: center; justify-content: center;
        font-size: 0.72rem; cursor: pointer;
    }
    #settingsModal .bg-cell:hover .bg-del { display: flex; }
    #settingsModal .bg-cell .bg-del:hover { background: rgba(255,82,82,0.2); }
    #settingsModal .upload-zone {
        border: 1px dashed rgba(0,240,255,0.28); border-radius: 10px;
        padding: 18px; text-align: center; cursor: pointer;
        transition: all .18s; color: #94a3b8; font-size: 0.82rem;
    }
    #settingsModal .upload-zone:hover, #settingsModal .upload-zone.dragover {
        border-color: #00f0ff; color: #e2e8f0;
        background: rgba(0,240,255,0.05);
    }
    #settingsModal .settings-section-title {
        font-size: 0.72rem; letter-spacing: .08em; text-transform: uppercase;
        color: #94a3b8; margin-bottom: 8px;
    }
    #settingsModal .settings-msg { font-size: 0.8rem; min-height: 20px; }
    `;

    const HTML = `
    <div class="modal fade" id="settingsModal" tabindex="-1">
      <div class="modal-dialog modal-lg">
        <div class="modal-content">
          <div class="modal-header">
            <h5 class="modal-title"><i class="bi bi-gear me-2"></i>设置</h5>
            <button class="btn-close" data-bs-dismiss="modal"></button>
          </div>
          <div class="settings-tabs">
            <div class="settings-tab active" data-tab="bg">背景图</div>
            <div class="settings-tab" data-tab="account">账号安全</div>
          </div>
          <div class="modal-body">

            <div data-pane="bg">
              <div class="settings-section-title">当前背景</div>
              <div class="mb-3 small" style="color:#00f0ff;font-family:monospace" id="stCurrent">-</div>

              <div class="settings-section-title">内置图片</div>
              <div class="bg-grid mb-3" id="stBuiltin"></div>

              <div class="settings-section-title">
                已上传 <span id="stUploadCount" style="color:#00f0ff"></span>
              </div>
              <div class="bg-grid mb-3" id="stUploaded"></div>
              <div id="stUploadedEmpty" class="text-muted small mb-3" style="display:none">
                暂无上传的图片
              </div>

              <div class="upload-zone" id="stZone">
                <i class="bi bi-cloud-arrow-up me-2"></i>
                点击选择或拖入图片　·　JPEG / PNG / WebP　·　最大 10MB
              </div>
              <input type="file" id="stFile" accept="image/jpeg,image/png,image/webp" hidden>
            </div>

            <div data-pane="account" style="display:none">
              <div class="mb-3">
                <label class="form-label">当前密码</label>
                <input type="password" class="form-control" id="stOldPwd" autocomplete="current-password">
              </div>
              <div class="mb-3">
                <label class="form-label">新密码</label>
                <input type="password" class="form-control" id="stNewPwd" autocomplete="new-password"
                       placeholder="至少 4 位">
              </div>
              <div class="mb-3">
                <label class="form-label">确认新密码</label>
                <input type="password" class="form-control" id="stNewPwd2" autocomplete="new-password">
              </div>
              <button class="btn btn-primary btn-sm" id="stChangePwd">修改密码</button>
              <div class="text-muted small mt-3">
                密码修改后当前会话仍然有效，下次登录使用新密码。
              </div>
            </div>

          </div>
          <div class="modal-footer">
            <div class="settings-msg me-auto" id="stMsg"></div>
            <button class="btn btn-outline-secondary btn-sm" id="stReset">恢复默认背景</button>
            <button class="btn btn-secondary" data-bs-dismiss="modal">关闭</button>
          </div>
        </div>
      </div>
    </div>
    `;

    let modal = null;
    let root = null;

    function ensureMounted() {
        if (root) return;
        const style = document.createElement('style');
        style.textContent = CSS;
        document.head.appendChild(style);

        const holder = document.createElement('div');
        holder.innerHTML = HTML;
        root = holder.firstElementChild;
        document.body.appendChild(root);

        bindEvents();
        modal = new bootstrap.Modal(root);
    }

    function $(id) {
        return root.querySelector('#' + id);
    }

    function msg(text, ok) {
        const el = $('stMsg');
        el.textContent = text || '';
        el.style.color = ok === false ? '#ff5252' : (ok === true ? '#00e676' : '#94a3b8');
        if (ok !== undefined) {
            setTimeout(function () {
                if (el.textContent === text) el.textContent = '';
            }, 4000);
        }
    }

    function bindEvents() {
        root.querySelectorAll('.settings-tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                root.querySelectorAll('.settings-tab').forEach(function (t) {
                    t.classList.toggle('active', t === tab);
                });
                root.querySelectorAll('[data-pane]').forEach(function (p) {
                    p.style.display = p.dataset.pane === tab.dataset.tab ? '' : 'none';
                });
                root.querySelector('#stReset').style.display =
                    tab.dataset.tab === 'bg' ? '' : 'none';
                msg('');
            });
        });

        const zone = $('stZone');
        const fileInput = $('stFile');
        zone.addEventListener('click', function () { fileInput.click(); });
        fileInput.addEventListener('change', function () {
            if (fileInput.files && fileInput.files[0]) doUpload(fileInput.files[0]);
            fileInput.value = '';
        });
        ['dragenter', 'dragover'].forEach(function (ev) {
            zone.addEventListener(ev, function (e) {
                e.preventDefault();
                zone.classList.add('dragover');
            });
        });
        ['dragleave', 'drop'].forEach(function (ev) {
            zone.addEventListener(ev, function (e) {
                e.preventDefault();
                zone.classList.remove('dragover');
            });
        });
        zone.addEventListener('drop', function (e) {
            const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
            if (f) doUpload(f);
        });

        $('stReset').addEventListener('click', doReset);
        $('stChangePwd').addEventListener('click', doChangePassword);
    }

    // ==================== 背景图 ====================

    function render(data) {
        $('stCurrent').textContent = data.current || '-';
        $('stUploadCount').textContent =
            '(' + data.uploaded.length + ' / ' + data.maxUpload + ')';

        $('stBuiltin').innerHTML = data.builtin
            .map(function (it) { return cell(it, data.current); }).join('');
        $('stUploaded').innerHTML = data.uploaded
            .map(function (it) { return cell(it, data.current); }).join('');
        $('stUploadedEmpty').style.display = data.uploaded.length ? 'none' : '';

        root.querySelectorAll('.bg-cell').forEach(function (el) {
            el.addEventListener('click', function (e) {
                if (e.target.closest('.bg-del')) return;
                doApply(el.dataset.url);
            });
            const del = el.querySelector('.bg-del');
            if (del) {
                del.addEventListener('click', function (e) {
                    e.stopPropagation();
                    doDelete(el.dataset.filename);
                });
            }
        });
    }

    function cell(it, current) {
        const isCur = it.url === current;
        const sizeKb = it.size ? Math.round(it.size / 1024) + 'KB' : '内置';
        return '<div class="bg-cell' + (isCur ? ' current' : '') + '"'
            + ' data-url="' + esc(it.url) + '"'
            + ' data-filename="' + esc(it.filename) + '"'
            + ' title="' + esc(it.filename) + '">'
            + '<img src="' + esc(it.url) + '" alt="" loading="lazy">'
            + '<span class="bg-tag">' + esc(sizeKb) + '</span>'
            + (isCur ? '<span class="bg-check"><i class="bi bi-check-lg"></i></span>' : '')
            + (it.builtin ? '' : '<span class="bg-del" title="删除"><i class="bi bi-trash"></i></span>')
            + '</div>';
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    async function load() {
        msg('加载中…');
        try {
            const r = await fetch('/api/settings/backgrounds');
            const j = await r.json();
            if (j.code !== 200) { msg(j.message || '加载失败', false); return; }
            render(j.data);
            msg('');
        } catch (e) {
            msg('加载失败：' + e.message, false);
        }
    }

    /** 背景图换了要立刻在当前页面生效，不然得刷新才看得到 */
    function applyToPage(url) {
        document.querySelectorAll('img.bg-img').forEach(function (img) {
            img.src = url + (url.indexOf('?') >= 0 ? '&' : '?') + 't=' + Date.now();
        });
    }

    async function doApply(url) {
        try {
            const r = await fetch('/api/settings/background', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url: url })
            });
            const j = await r.json();
            if (j.code !== 200) { msg(j.message || '切换失败', false); return; }
            applyToPage(j.data.url);
            await load();
            msg('已应用', true);
        } catch (e) {
            msg('切换失败：' + e.message, false);
        }
    }

    async function doUpload(file) {
        if (file.size > 10 * 1024 * 1024) {
            msg('图片超过 10MB 限制', false);
            return;
        }
        msg('上传中…');
        try {
            const fd = new FormData();
            fd.append('file', file);
            const r = await fetch('/api/settings/background/upload', { method: 'POST', body: fd });
            const j = await r.json();
            if (j.code !== 200) { msg(j.message || '上传失败', false); return; }
            applyToPage(j.data.url);
            await load();
            msg('上传成功并已应用', true);
        } catch (e) {
            msg('上传失败：' + e.message, false);
        }
    }

    async function doDelete(filename) {
        if (!confirm('确定删除图片 ' + filename + '？')) return;
        try {
            const r = await fetch('/api/settings/background/upload/'
                + encodeURIComponent(filename), { method: 'DELETE' });
            const j = await r.json();
            if (j.code !== 200) { msg(j.message || '删除失败', false); return; }
            applyToPage(j.data.current);
            await load();
            msg('已删除', true);
        } catch (e) {
            msg('删除失败：' + e.message, false);
        }
    }

    async function doReset() {
        try {
            const r = await fetch('/api/settings/background/reset', { method: 'POST' });
            const j = await r.json();
            if (j.code !== 200) { msg(j.message || '操作失败', false); return; }
            applyToPage(j.data.url);
            await load();
            msg('已恢复默认背景', true);
        } catch (e) {
            msg('操作失败：' + e.message, false);
        }
    }

    // ==================== 改密码 ====================

    async function doChangePassword() {
        const oldPwd = $('stOldPwd').value;
        const newPwd = $('stNewPwd').value;
        const newPwd2 = $('stNewPwd2').value;
        if (!oldPwd || !newPwd) { msg('请填写当前密码与新密码', false); return; }
        if (newPwd !== newPwd2) { msg('两次输入的新密码不一致', false); return; }
        try {
            const r = await fetch('/api/auth/change-password', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ oldPassword: oldPwd, newPassword: newPwd })
            });
            const j = await r.json();
            if (j.code !== 200) { msg(j.message || '修改失败', false); return; }
            $('stOldPwd').value = '';
            $('stNewPwd').value = '';
            $('stNewPwd2').value = '';
            msg('密码已修改', true);
        } catch (e) {
            msg('修改失败：' + e.message, false);
        }
    }

    // ==================== 对外入口 ====================

    window.openSettingsModal = function () {
        ensureMounted();
        modal.show();
        load();
    };
})();
