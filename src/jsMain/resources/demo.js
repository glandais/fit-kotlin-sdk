/*
    fit-kotlin-sdk browser demo. Hand-written, local to this repository (src/ is generated
    by fitgen, this is not). Plain ES2020, no build step, no dependencies: what the browser
    loads is exactly this file.

    The Kotlin/JS bundle publishes its @JsExport declarations on a UMD global named after
    the Gradle project — globalThis['fit-kotlin-sdk'] — and everything below goes through
    the four functions it exposes: decodeFit, isFitFile, fitFieldInfo, fitProfileVersion.
*/

'use strict';

(() => {
    const sdk = findSdk();

    const el = (id) => document.getElementById(id);
    const dropzone = el('dropzone');
    const statusLine = el('status');
    const results = el('results');

    /** Last decode, kept so the message table and the JSON download need no re-decode. */
    let state = null;

    // -------------------------------------------------------------------- bootstrap

    if (!sdk) {
        setStatus('The decoder bundle did not load. Rebuild with `./gradlew demo`.', true);
        return;
    }

    el('profile-version').textContent = 'FIT profile ' + sdk.fitProfileVersion();
    setStatus('Ready.');

    el('choose-btn').addEventListener('click', () => el('file-input').click());
    el('file-input').addEventListener('change', (event) => {
        const file = event.target.files[0];
        if (file) loadFile(file);
    });
    el('sample-btn').addEventListener('click', loadSample);
    el('json-btn').addEventListener('click', downloadJson);
    el('unknown-toggle').addEventListener('change', () => {
        // Unknown data changes what the decoder keeps, so it means decoding again.
        if (state) decode(state.bytes, state.name);
    });

    for (const type of ['dragenter', 'dragover']) {
        dropzone.addEventListener(type, (event) => {
            event.preventDefault();
            dropzone.classList.add('dragover');
        });
    }
    for (const type of ['dragleave', 'drop']) {
        dropzone.addEventListener(type, () => dropzone.classList.remove('dragover'));
    }
    dropzone.addEventListener('drop', (event) => {
        event.preventDefault();
        const file = event.dataTransfer.files[0];
        if (file) loadFile(file);
    });

    window.addEventListener('resize', () => {
        if (!state) return;
        drawTrack(state.track);
        drawChart(state.series);
    });

    // ------------------------------------------------------------------ loading

    async function loadFile(file) {
        setStatus('Reading ' + file.name + '…');
        try {
            const buffer = await file.arrayBuffer();
            decode(new Int8Array(buffer), file.name);
        } catch (error) {
            setStatus('Could not read that file: ' + error, true);
        }
    }

    async function loadSample() {
        setStatus('Fetching the sample activity…');
        try {
            const response = await fetch('sample-activity.fit');
            if (!response.ok) throw new Error('HTTP ' + response.status);
            decode(new Int8Array(await response.arrayBuffer()), 'sample-activity.fit');
        } catch (error) {
            setStatus('Could not fetch the sample: ' + error, true);
        }
    }

    function decode(bytes, name) {
        if (!sdk.isFitFile(bytes)) {
            setStatus(name + ' does not start with a FIT header.', true);
            return;
        }

        const started = performance.now();
        const result = sdk.decodeFit(bytes, {
            includeUnknownData: el('unknown-toggle').checked,
        });
        const elapsed = performance.now() - started;

        state = {
            bytes,
            name,
            result,
            elapsed,
            byType: groupByType(result.mesgs),
            track: trackOf(result),
            series: seriesOf(result),
            selected: null,
        };

        results.hidden = false;
        renderSummary();
        renderErrors();
        renderTypeList();
        drawTrack(state.track);
        drawChart(state.series);
        setStatus(
            name +
                ' — ' +
                fmtInt(result.mesgs.length) +
                ' messages decoded in ' +
                elapsed.toFixed(0) +
                ' ms',
        );
    }

    // ------------------------------------------------------------------ summary

    function renderSummary() {
        const { result, elapsed } = state;
        const fileId = first(result, 'fileIdMesgs');
        const session = first(result, 'sessionMesgs');
        const cards = [];

        // A zero here is never interesting: FIT writes 0 for "unset" in every field this
        // summarises (product id, ascent, calories), so a `PRODUCT 0` card is noise.
        const add = (label, value) => {
            if (value !== undefined && value !== null && value !== '' && value !== 0) {
                cards.push({ label, value });
            }
        };

        add('File type', fileId && fileId.fields.type);
        add('Manufacturer', fileId && fileId.fields.manufacturer);
        add(
            'Product',
            fileId && (fileId.fields.garminProduct || fileId.fields.productName || fileId.fields.product),
        );
        add('Created', fileId && fmtDate(fileId.fields.timeCreated));
        add('Sport', session && session.fields.sport);
        add('Distance', session && fmtDistance(session.fields.totalDistance));
        add('Moving time', session && fmtDuration(session.fields.totalTimerTime));
        add('Ascent', session && session.fields.totalAscent && session.fields.totalAscent + ' m');
        add('Avg HR', session && session.fields.avgHeartRate && session.fields.avgHeartRate + ' bpm');
        add('Avg power', session && session.fields.avgPower && session.fields.avgPower + ' W');
        add('Calories', session && session.fields.totalCalories);
        add('Messages', fmtInt(result.mesgs.length));
        add('Message types', fmtInt(state.byType.size));
        add('Decoded in', elapsed.toFixed(0) + ' ms');

        el('summary').innerHTML = cards
            .map(
                (card) =>
                    '<div class="card"><div class="card-label">' +
                    escapeHtml(card.label) +
                    '</div><div class="card-value">' +
                    escapeHtml(String(card.value)) +
                    '</div></div>',
            )
            .join('');
    }

    function renderErrors() {
        const box = el('errors');
        const errors = state.result.errors;
        if (!errors.length) {
            box.hidden = true;
            return;
        }
        box.hidden = false;
        box.innerHTML =
            '<strong>' +
            errors.length +
            (errors.length === 1 ? ' problem' : ' problems') +
            ' — everything decoded before it is still shown</strong><ul>' +
            errors
                .map(
                    (error) =>
                        '<li>' +
                        escapeHtml(error.message) +
                        (error.bytePosition >= 0 ? ' (byte ' + error.bytePosition + ')' : '') +
                        '</li>',
                )
                .join('') +
            '</ul>';
    }

    // ------------------------------------------------------------- message table

    function groupByType(mesgs) {
        const byType = new Map();
        for (const mesg of mesgs) {
            let bucket = byType.get(mesg.name);
            if (!bucket) byType.set(mesg.name, (bucket = []));
            bucket.push(mesg);
        }
        return new Map([...byType].sort((a, b) => b[1].length - a[1].length));
    }

    function renderTypeList() {
        const list = el('mesg-types');
        list.innerHTML = '';
        for (const [name, mesgs] of state.byType) {
            const li = document.createElement('li');
            const button = document.createElement('button');
            button.type = 'button';
            button.innerHTML =
                '<span>' +
                escapeHtml(name) +
                '</span><span class="count">' +
                fmtInt(mesgs.length) +
                '</span>';
            button.addEventListener('click', () => selectType(name));
            li.appendChild(button);
            list.appendChild(li);
        }
        const firstName = state.byType.keys().next().value;
        if (firstName) selectType(firstName);
    }

    const MAX_ROWS = 500;

    function selectType(name) {
        state.selected = name;
        const buttons = el('mesg-types').querySelectorAll('button');
        let index = 0;
        for (const key of state.byType.keys()) {
            buttons[index].classList.toggle('on', key === name);
            index++;
        }

        const mesgs = state.byType.get(name) || [];
        // Column order follows first appearance, which is the producer's field order.
        const columns = [];
        const seen = new Set();
        for (const mesg of mesgs) {
            for (const key of Object.keys(mesg.fields)) {
                if (!seen.has(key)) {
                    seen.add(key);
                    columns.push(key);
                }
            }
            for (const key of Object.keys(mesg.developerFields)) {
                const label = key + ' *';
                if (!seen.has(label)) {
                    seen.add(label);
                    columns.push(label);
                }
            }
        }

        const rows = mesgs.slice(0, MAX_ROWS);
        el('table-note').textContent =
            fmtInt(mesgs.length) +
            ' × ' +
            name +
            ', ' +
            columns.length +
            ' fields' +
            (rows.length < mesgs.length ? ' — showing the first ' + MAX_ROWS : '') +
            (columns.some((column) => column.endsWith(' *')) ? ' (* developer field)' : '');

        const table = el('mesg-table');
        table.querySelector('thead').innerHTML =
            '<tr><th>#</th>' +
            columns
                .map((column) => {
                    const info = fieldInfo(name, column);
                    const units = info && info.units ? '<span class="units">' + escapeHtml(info.units) + '</span>' : '';
                    return '<th>' + escapeHtml(column) + units + '</th>';
                })
                .join('') +
            '</tr>';

        table.querySelector('tbody').innerHTML = rows
            .map((mesg) => {
                const cells = columns.map((column) => {
                    const value = column.endsWith(' *')
                        ? mesg.developerFields[column.slice(0, -2)]
                        : mesg.fields[column];
                    const numeric = typeof value === 'number';
                    return (
                        '<td' + (numeric ? ' class="num"' : '') + '>' + escapeHtml(fmtValue(value)) + '</td>'
                    );
                });
                return '<tr><td class="num">' + mesg.index + '</td>' + cells.join('') + '</tr>';
            })
            .join('');
    }

    /** Profile metadata for one column, memoised: the table asks for the same pairs often. */
    const fieldInfoCache = new Map();

    function fieldInfo(mesgName, fieldName) {
        if (fieldName.endsWith(' *')) return null;
        const key = mesgName + '.' + fieldName;
        if (!fieldInfoCache.has(key)) {
            fieldInfoCache.set(key, sdk.fitFieldInfo(mesgName, fieldName) || null);
        }
        return fieldInfoCache.get(key);
    }

    // ------------------------------------------------------------------- track

    const SEMICIRCLES_TO_DEGREES = 180 / Math.pow(2, 31);

    function trackOf(result) {
        const points = [];
        for (const mesg of result.messages.recordMesgs || []) {
            const lat = mesg.fields.positionLat;
            const lon = mesg.fields.positionLong;
            if (typeof lat === 'number' && typeof lon === 'number') {
                points.push([lon * SEMICIRCLES_TO_DEGREES, lat * SEMICIRCLES_TO_DEGREES]);
            }
        }
        return points;
    }

    function drawTrack(points) {
        const canvas = el('track');
        const ctx = prepareCanvas(canvas);
        const { width, height } = canvas.getBoundingClientRect();

        if (!points.length) {
            el('track-note').textContent = 'no position data';
            drawPlaceholder(ctx, width, height, 'No GPS positions in this file');
            return;
        }
        el('track-note').textContent = fmtInt(points.length) + ' points';

        const lons = points.map((p) => p[0]);
        const lats = points.map((p) => p[1]);
        const minLon = Math.min(...lons);
        const maxLon = Math.max(...lons);
        const minLat = Math.min(...lats);
        const maxLat = Math.max(...lats);

        // Longitude degrees shrink with latitude; without this the track is stretched.
        const midLat = ((minLat + maxLat) / 2) * (Math.PI / 180);
        const spanX = Math.max((maxLon - minLon) * Math.cos(midLat), 1e-9);
        const spanY = Math.max(maxLat - minLat, 1e-9);

        const pad = 16;
        const scale = Math.min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY);
        const offsetX = (width - spanX * scale) / 2;
        const offsetY = (height - spanY * scale) / 2;

        ctx.beginPath();
        points.forEach(([lon, lat], index) => {
            const x = offsetX + (lon - minLon) * Math.cos(midLat) * scale;
            const y = height - (offsetY + (lat - minLat) * scale);
            if (index === 0) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
        });
        ctx.strokeStyle = cssVar('--chart-1');
        ctx.lineWidth = 2;
        ctx.lineJoin = 'round';
        ctx.stroke();

        const mark = (point, color) => {
            const x = offsetX + (point[0] - minLon) * Math.cos(midLat) * scale;
            const y = height - (offsetY + (point[1] - minLat) * scale);
            ctx.beginPath();
            ctx.arc(x, y, 4, 0, 2 * Math.PI);
            ctx.fillStyle = color;
            ctx.fill();
        };
        mark(points[0], cssVar('--chart-2'));
        mark(points[points.length - 1], cssVar('--chart-3'));
    }

    // ------------------------------------------------------------------ series

    const SERIES = [
        { key: 'altitude', label: 'Altitude', fields: ['enhancedAltitude', 'altitude'], color: '--chart-1' },
        { key: 'speed', label: 'Speed', fields: ['enhancedSpeed', 'speed'], color: '--chart-2' },
        { key: 'heartRate', label: 'Heart rate', fields: ['heartRate'], color: '--chart-3' },
        { key: 'cadence', label: 'Cadence', fields: ['cadence'], color: '--chart-4' },
        { key: 'power', label: 'Power', fields: ['power'], color: '--chart-5' },
    ];

    function seriesOf(result) {
        const records = result.messages.recordMesgs || [];
        const series = [];
        for (const spec of SERIES) {
            const field = spec.fields.find((name) =>
                records.some((mesg) => typeof mesg.fields[name] === 'number'),
            );
            if (!field) continue;
            const values = records.map((mesg) =>
                typeof mesg.fields[field] === 'number' ? mesg.fields[field] : null,
            );
            const info = sdk.fitFieldInfo('record', field);
            series.push({
                ...spec,
                field,
                values,
                units: info ? info.units : '',
                enabled: series.length < 3,
            });
        }
        return series;
    }

    function drawChart(series) {
        const toggles = el('series-toggles');
        toggles.innerHTML = '';
        for (const item of series) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'toggle' + (item.enabled ? ' on' : '');
            button.style.color = item.enabled ? cssVar(item.color) : '';
            button.innerHTML = '<span class="swatch"></span>' + escapeHtml(item.label);
            button.addEventListener('click', () => {
                item.enabled = !item.enabled;
                drawChart(series);
            });
            toggles.appendChild(button);
        }

        const canvas = el('chart');
        const ctx = prepareCanvas(canvas);
        const { width, height } = canvas.getBoundingClientRect();

        const active = series.filter((item) => item.enabled);
        if (!active.length) {
            drawPlaceholder(
                ctx,
                width,
                height,
                series.length ? 'Pick a series above' : 'No record messages in this file',
            );
            return;
        }

        const pad = { top: 12, right: 12, bottom: 22, left: 12 };
        const plotWidth = width - pad.left - pad.right;
        const plotHeight = height - pad.top - pad.bottom;

        ctx.strokeStyle = cssVar('--line');
        ctx.lineWidth = 1;
        for (let i = 0; i <= 4; i++) {
            const y = pad.top + (plotHeight * i) / 4;
            ctx.beginPath();
            ctx.moveTo(pad.left, y);
            ctx.lineTo(pad.left + plotWidth, y);
            ctx.stroke();
        }

        // Each series is scaled to its own range: they share an X axis (the record index),
        // never a Y one — heart rate and altitude have nothing comparable about them.
        for (const item of active) {
            const numbers = item.values.filter((value) => value !== null);
            const min = Math.min(...numbers);
            const max = Math.max(...numbers);
            const span = max - min || 1;

            ctx.beginPath();
            let drawing = false;
            item.values.forEach((value, index) => {
                if (value === null) {
                    drawing = false;
                    return;
                }
                const x = pad.left + (plotWidth * index) / Math.max(item.values.length - 1, 1);
                const y = pad.top + plotHeight - ((value - min) / span) * plotHeight;
                if (!drawing) {
                    ctx.moveTo(x, y);
                    drawing = true;
                } else {
                    ctx.lineTo(x, y);
                }
            });
            ctx.strokeStyle = cssVar(item.color);
            ctx.lineWidth = 1.6;
            ctx.stroke();

            // Legend over the plot, on a panel-coloured strip: the lines run the full
            // width, so there is nowhere else to put it that does not shrink the chart.
            const label =
                item.label + ': ' + fmtNumber(min) + '–' + fmtNumber(max) + (item.units ? ' ' + item.units : '');
            const y = pad.top + 12 + 14 * active.indexOf(item);
            ctx.font = '11px system-ui, sans-serif';
            ctx.globalAlpha = 0.85;
            ctx.fillStyle = cssVar('--panel');
            ctx.fillRect(pad.left + 2, y - 10, ctx.measureText(label).width + 8, 13);
            ctx.globalAlpha = 1;
            ctx.fillStyle = cssVar(item.color);
            ctx.fillText(label, pad.left + 6, y);
        }

        ctx.fillStyle = cssVar('--ink-soft');
        ctx.font = '11px system-ui, sans-serif';
        ctx.fillText('record 0', pad.left, height - 6);
        const lastLabel = 'record ' + fmtInt(active[0].values.length - 1);
        ctx.fillText(lastLabel, pad.left + plotWidth - ctx.measureText(lastLabel).width, height - 6);
    }

    // -------------------------------------------------------------------- JSON

    function downloadJson() {
        if (!state) return;
        const payload = {
            file: state.name,
            profileVersion: state.result.profileVersion,
            errors: state.result.errors.map((error) => ({
                message: error.message,
                bytePosition: error.bytePosition,
            })),
            messages: state.result.mesgs.map((mesg) => ({
                name: mesg.name,
                num: mesg.num,
                index: mesg.index,
                fields: mesg.fields,
                developerFields: Object.keys(mesg.developerFields).length ? mesg.developerFields : undefined,
            })),
        };
        const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = state.name.replace(/\.fit$/i, '') + '.json';
        link.click();
        URL.revokeObjectURL(url);
    }

    // ----------------------------------------------------------------- helpers

    function findSdk() {
        const named = globalThis['fit-kotlin-sdk'];
        if (named && typeof named.decodeFit === 'function') return named;
        // Renaming the webpack output would change that key, so fall back to a scan
        // rather than failing with an empty page.
        for (const key of Object.keys(globalThis)) {
            const candidate = globalThis[key];
            if (candidate && typeof candidate.decodeFit === 'function') return candidate;
        }
        return null;
    }

    function first(result, key) {
        const bucket = result.messages[key];
        return bucket && bucket.length ? bucket[0] : null;
    }

    function prepareCanvas(canvas) {
        const ratio = globalThis.devicePixelRatio || 1;
        const { width, height } = canvas.getBoundingClientRect();
        canvas.width = Math.max(1, Math.round(width * ratio));
        canvas.height = Math.max(1, Math.round(height * ratio));
        const ctx = canvas.getContext('2d');
        ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
        ctx.clearRect(0, 0, width, height);
        return ctx;
    }

    function drawPlaceholder(ctx, width, height, text) {
        ctx.fillStyle = cssVar('--ink-soft');
        ctx.font = '13px system-ui, sans-serif';
        ctx.fillText(text, (width - ctx.measureText(text).width) / 2, height / 2);
    }

    function cssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    function setStatus(text, isError) {
        statusLine.textContent = text;
        statusLine.classList.toggle('error', Boolean(isError));
    }

    function fmtValue(value) {
        if (value === undefined || value === null) return '';
        if (value instanceof Date) return fmtDate(value);
        if (Array.isArray(value)) return value.map(fmtValue).join(', ');
        if (typeof value === 'number') return fmtNumber(value);
        return String(value);
    }

    function fmtNumber(value) {
        if (!Number.isFinite(value)) return String(value);
        if (Number.isInteger(value)) return String(value);
        return String(Math.round(value * 1000) / 1000);
    }

    function fmtInt(value) {
        return Number(value).toLocaleString();
    }

    function fmtDate(value) {
        if (!(value instanceof Date)) return '';
        return value.toISOString().replace('T', ' ').replace('.000Z', 'Z');
    }

    function fmtDistance(metres) {
        if (typeof metres !== 'number') return undefined;
        return metres >= 1000 ? (metres / 1000).toFixed(2) + ' km' : Math.round(metres) + ' m';
    }

    function fmtDuration(seconds) {
        if (typeof seconds !== 'number') return undefined;
        const total = Math.round(seconds);
        const h = Math.floor(total / 3600);
        const m = Math.floor((total % 3600) / 60);
        const s = total % 60;
        const pad = (n) => String(n).padStart(2, '0');
        return h ? h + ':' + pad(m) + ':' + pad(s) : m + ':' + pad(s);
    }

    function escapeHtml(text) {
        return String(text).replace(
            /[&<>"']/g,
            (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char],
        );
    }
})();
