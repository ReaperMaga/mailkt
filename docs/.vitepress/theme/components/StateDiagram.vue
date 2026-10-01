<template>
  <figure class="mk-diagram">
    <div class="mk-diagram-scroll">
      <svg viewBox="0 0 830 300" role="img" aria-labelledby="mk-state-title">
        <title id="mk-state-title">Mailbox state machine</title>
        <defs>
          <marker id="mk-state-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" class="mk-head" />
          </marker>
        </defs>

        <!-- open() -->
        <circle cx="12" cy="118" r="6" class="mk-start" />
        <line x1="18" y1="118" x2="66" y2="118" class="mk-edge" marker-end="url(#mk-state-arrow)" />
        <text x="40" y="108" text-anchor="middle" class="mk-label">open()</text>

        <!-- States -->
        <rect x="70" y="90" width="150" height="56" rx="28" class="mk-box focus" />
        <text x="145" y="123" text-anchor="middle" class="mk-title">Connected</text>

        <rect x="355" y="90" width="160" height="56" rx="28" class="mk-box" />
        <text x="435" y="114" text-anchor="middle" class="mk-title">Reconnecting</text>
        <text x="435" y="133" text-anchor="middle" class="mk-sub">attempt, reason</text>

        <rect x="665" y="90" width="150" height="56" rx="28" class="mk-box" />
        <text x="740" y="114" text-anchor="middle" class="mk-title">Failed</text>
        <text x="740" y="133" text-anchor="middle" class="mk-sub">recoverable</text>

        <rect x="315" y="222" width="240" height="56" rx="28" class="mk-box" />
        <text x="435" y="255" text-anchor="middle" class="mk-title">AuthenticationRequired</text>

        <rect x="665" y="222" width="150" height="56" rx="28" class="mk-box terminal" />
        <text x="740" y="255" text-anchor="middle" class="mk-title">Closed</text>

        <!-- Connected <-> Reconnecting -->
        <line x1="220" y1="106" x2="351" y2="106" class="mk-edge" marker-end="url(#mk-state-arrow)" />
        <text x="287" y="97" text-anchor="middle" class="mk-label">connection lost</text>
        <line x1="355" y1="132" x2="224" y2="132" class="mk-edge" marker-end="url(#mk-state-arrow)" />
        <text x="287" y="152" text-anchor="middle" class="mk-label">recovered</text>

        <!-- Reconnecting -> Failed -->
        <line x1="515" y1="118" x2="661" y2="118" class="mk-edge" marker-end="url(#mk-state-arrow)" />
        <text x="590" y="108" text-anchor="middle" class="mk-label">retries exhausted</text>

        <!-- Reconnecting -> AuthenticationRequired -->
        <line x1="435" y1="146" x2="435" y2="218" class="mk-edge" marker-end="url(#mk-state-arrow)" />
        <text x="445" y="187" class="mk-label">refresh rejected</text>

        <!-- AuthenticationRequired -> Connected -->
        <path d="M315,250 H145 V150" class="mk-edge" marker-end="url(#mk-state-arrow)" />
        <text x="230" y="241" text-anchor="middle" class="mk-label">reconnect()</text>

        <!-- Failed -> Connected -->
        <path d="M740,90 V34 H145 V86" class="mk-edge" marker-end="url(#mk-state-arrow)" />
        <text x="442" y="26" text-anchor="middle" class="mk-label">reconnect()</text>

        <!-- Failed -> Closed -->
        <line x1="740" y1="146" x2="740" y2="218" class="mk-edge ret" marker-end="url(#mk-state-arrow)" />
        <text x="750" y="187" class="mk-label">close()</text>
      </svg>
    </div>
    <figcaption class="mk-legend">
      <span><i class="sw focus" />Healthy</span>
      <span><i class="sw" />Recovering or waiting</span>
      <span><i class="sw terminal" />Terminal</span>
      <span class="mk-legend-note">close() is accepted from every state</span>
    </figcaption>
  </figure>
</template>
