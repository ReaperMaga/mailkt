<template>
  <figure class="mk-diagram">
    <div class="mk-diagram-scroll">
      <svg viewBox="0 0 820 390" role="img" aria-labelledby="mk-arch-title">
        <title id="mk-arch-title">How the provider client, Mailbox, your stores and the mail servers relate</title>
        <defs>
          <marker id="mk-arch-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" class="mk-head" />
          </marker>
        </defs>

        <text x="120" y="30" text-anchor="middle" class="mk-col">YOUR BACKEND</text>
        <text x="420" y="30" text-anchor="middle" class="mk-col">MAILKT</text>
        <text x="710" y="30" text-anchor="middle" class="mk-col">PROVIDER</text>

        <!-- Your backend -->
        <rect x="20" y="52" width="200" height="56" rx="6" class="mk-box data" />
        <text x="120" y="76" text-anchor="middle" class="mk-title">TokenStore</text>
        <text x="120" y="94" text-anchor="middle" class="mk-sub">opaque token bytes</text>

        <rect x="20" y="122" width="200" height="56" rx="6" class="mk-box data" />
        <text x="120" y="146" text-anchor="middle" class="mk-title" style="font-size: 14px">AuthorizationSessionStore</text>
        <text x="120" y="164" text-anchor="middle" class="mk-sub">pending authorizations</text>

        <rect x="20" y="272" width="200" height="56" rx="6" class="mk-box data" />
        <text x="120" y="296" text-anchor="middle" class="mk-title">Your code</text>
        <text x="120" y="314" text-anchor="middle" class="mk-sub">processing, checkpoints</text>

        <!-- MailKT -->
        <rect x="310" y="55" width="220" height="110" rx="6" class="mk-box" />
        <text x="420" y="104" text-anchor="middle" class="mk-title">Gmail / Outlook</text>
        <text x="420" y="122" text-anchor="middle" class="mk-sub">authorize, refresh tokens</text>

        <rect x="310" y="222" width="220" height="150" rx="6" class="mk-box focus" />
        <text x="420" y="248" text-anchor="middle" class="mk-title">Mailbox</text>
        <g v-for="(c, i) in ['folders', 'messages', 'conversations', 'outbox']" :key="c">
          <rect :x="326 + (i % 2) * 100" :y="264 + Math.floor(i / 2) * 50" width="88" height="34" rx="4" class="mk-chip" />
          <text :x="370 + (i % 2) * 100" :y="285 + Math.floor(i / 2) * 50" text-anchor="middle" class="mk-chip-text">{{ c }}</text>
        </g>

        <!-- Provider -->
        <rect x="620" y="75" width="180" height="70" rx="6" class="mk-box external" />
        <text x="710" y="104" text-anchor="middle" class="mk-title">OAuth server</text>
        <text x="710" y="122" text-anchor="middle" class="mk-sub">Google · Microsoft Entra</text>

        <rect x="620" y="265" width="180" height="70" rx="6" class="mk-box external" />
        <text x="710" y="294" text-anchor="middle" class="mk-title">Mail servers</text>
        <text x="710" y="312" text-anchor="middle" class="mk-sub">IMAP · SMTP</text>

        <!-- Edges -->
        <line x1="306" y1="80" x2="224" y2="80" class="mk-edge" marker-start="url(#mk-arch-arrow)" marker-end="url(#mk-arch-arrow)" />
        <line x1="306" y1="150" x2="224" y2="150" class="mk-edge" marker-start="url(#mk-arch-arrow)" marker-end="url(#mk-arch-arrow)" />
        <line x1="534" y1="110" x2="616" y2="110" class="mk-edge" marker-start="url(#mk-arch-arrow)" marker-end="url(#mk-arch-arrow)" />
        <text x="575" y="102" text-anchor="middle" class="mk-label">OAuth2</text>

        <line x1="420" y1="165" x2="420" y2="218" class="mk-edge" marker-end="url(#mk-arch-arrow)" />
        <text x="428" y="196" class="mk-label">open(email)</text>

        <line x1="534" y1="300" x2="616" y2="300" class="mk-edge" marker-start="url(#mk-arch-arrow)" marker-end="url(#mk-arch-arrow)" />
        <text x="575" y="292" text-anchor="middle" class="mk-label">XOAUTH2</text>

        <line x1="306" y1="300" x2="224" y2="300" class="mk-edge" marker-end="url(#mk-arch-arrow)" />
        <text x="265" y="292" text-anchor="middle" class="mk-label">Flow</text>
      </svg>
    </div>
    <figcaption class="mk-legend">
      <span><i class="sw focus" />Lifecycle handle</span>
      <span><i class="sw" />MailKT</span>
      <span><i class="sw data" />You implement</span>
      <span><i class="sw external" />External</span>
    </figcaption>
  </figure>
</template>
