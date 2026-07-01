---
name: Verse Landing Page
colors:
  background: '#0F0F0F' # Immersive page background
  surface: '#141414' # Dark container card background (iPodChassisDark)
  surface-bright: '#2A2A2A' # Sleek metallic chassis background (iPodChassis)
  on-surface: '#FFFFFF' # Primary white text
  on-surface-variant: '#99FFFFFF' # Muted text (iPodTextMuted)
  outline: '#2A2A2A' # Dark border color
  primary: '#B11623' # Signature Crimson Red accent color (iPodAccentBlue)
  on-primary: '#FFFFFF' # Text on primary buttons
  secondary: '#1F1F1F' # Tactile wheel gray (iPodClickWheel)
  tertiary: '#66FFFFFF' # Dim gray details (iPodTextDim)
typography:
  headline-xl:
    fontFamily: Outfit, Inter, sans-serif
    fontSize: 48px
    fontWeight: '800'
    lineHeight: 56px
    letterSpacing: -0.03em
  headline-lg:
    fontFamily: Outfit, Inter, sans-serif
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Inter, sans-serif
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  body-lg:
    fontFamily: Inter, sans-serif
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-sm:
    fontFamily: Inter, sans-serif
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 22px
  mono-technical:
    fontFamily: JetBrains Mono, Courier New, monospace
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: 0.05em
rounded:
  sm: 4px
  DEFAULT: 8px
  md: 12px
  lg: 16px
  xl: 32px
  full: 9999px
---

# Design Brief & Context: Verse Landing Page

This document provides detailed context and visual specifications for building the official landing page of **Verse** (a premium iPod-inspired YouTube Music Player).

---

## 1. Brand Strategy & Visual Direction

### Brand Identity
- **Name:** Verse
- **Tagline:** Retro Tactility Meets Modern Streaming.
- **Concept:** A modern-retro crossover that bridges the physical, muscle-memory tactile feel of a classic click-wheel media player with the massive music catalog of YouTube.
- **Tone:** Premium, sophisticated, dark, tactile, nostalgic, high-fidelity.

### Design Aesthetic (Modern Retro Neumorphism)
- **Deep Dark Mode:** Force dark theme entirely. The page background should use a pure pitch-black or near-black gradient to draw all eyes to the high-contrast device mockups.
- **Metallic & Industrial:** Elements should mimic anodized charcoal aluminum, with subtle linear gradients and 1px outer highlights ("light leaks") along borders to simulate machined metallic bevels.
- **Vibrant Crimson Red Glows:** The primary brand accent color is **Crimson Red (`#B11623`)**. It should be used sparingly but impactfully as neon/glow shadows, selection rings, progress indicators, and interactive states to stand out from the charcoal grey base.
- **Glassmorphism:** Sub-components on the screen and overlays use high background blur (30-40px) to achieve a modern premium digital layer.

---

## 2. Interactive Features to Highlight

The landing page must showcase the key capabilities of the mobile application:
1. **Interactive Click Wheel:** Tactile scroll navigation, center select button, and haptic wheel rotation logic.
2. **Dual-Mode UI:** 
   - **Hardware Mode:** An exact visual simulation of a metallic iPod device containing an OLED screen and a matte click wheel.
   - **Expanded Mode:** Maximizes screen space, sliding down the click wheel to show full search results, playlists, queues, and library interfaces.
3. **Seamless YouTube Integration:** Real-time search, dynamic web-bridge player, background execution, and audio caching.
4. **Nostalgic OLED Interface:** Pixel-perfect retro list scrolling, monospaced status bars, and classic now-playing views with dynamic album art and text layouts.

---

## 3. Structural Wireframe & Copy Guide

### Hero Section: Visual Hardware Centerpiece
- **Headline (Left):** "Rewind the Clock. Stream the Future."
- **Sub-headline:** "Verse brings the tactile romance of physical click-wheel media players to your modern YouTube streaming library. Experience music you can feel."
- **CTAs:** 
  - Primary: "Download for Android" (crimson-glowing pill button)
  - Secondary: "Interactive Demo" (sleek metallic border button with soft shadow)
- **Visual Asset (Right):** A 3D-styled CSS/SVG mockup of the device:
  - An outer frame with rounded corners (`rounded-xl` / 32px) styled with a metallic linear gradient from `#2A2A2A` (iPodChassis) to `#141414` (iPodChassisDark).
  - An inner black screen cavity (`#000000`) showing the "Now Playing" UI: album art, song title, dynamic seek bar, and a glowing crimson indicator.
  - A bottom circle representing the matte Click Wheel (`#1F1F1F`) with engraved labels ("MENU", Play/Pause, Forward, Backward) and a metallic center selection cap.

### Feature Showcase: The Anatomy of Tactility
- **Feature 1: The Tactical Click Wheel**
  - *Copy:* "Scroll. Click. Feel."
  - *Detail:* "Control your playback naturally. Drag your finger around the circular touch trackpad to adjust volume, scroll tracklists, and seek songs with precise haptic clicks."
- **Feature 2: Dual Interface Configurations**
  - *Copy:* "Hardware or Expanded. Your Choice."
  - *Detail:* "Play in nostalgia-rich device mode, or expand to full-screen view when you need deep search directories, lyrics, and comprehensive playlist edits."
- **Feature 3: The Whole YouTube Catalog**
  - *Copy:* "Infinite Streaming. Zero Bloat."
  - *Detail:* "Direct web integration searches and streams audio from YouTube, packing it into an clean, ad-free hardware simulation."

### Interactive Component: The Click Wheel Web Simulator
Include a live web component on the page:
- A beautifully rendered Click Wheel.
- Hovering/dragging on the circle triggers a rotating rotation indicator on a mock display above it.
- Clicking the center select button triggers a glowing red pulse.
- This creates immediate visitor engagement and showcases the core differentiator of the app.

### Technical & Aesthetics Highlight
- A list of premium features using monospaced labels (`mono-technical` typography):
  - `CHASSIS: Metallic Charcoal (Anodized Aluminum)`
  - `HIGHLIGHT: Crimson Red Neon / #B11623`
  - `DATABASE: SQLite Offline Room Cache`
  - `INTEGRATION: YouTube WebView Playback Engine`
  - `HAPTICS: Low-latency Tactile Feedback`

### CTA Footer: Grab the APK
- A central premium dark card (`#141414`) with a crimson outline and gradient glow.
- **Copy:** "Take control of your music catalog today."
- **Buttons:** "Download Verse APK" (Direct download) & "Source Code on GitHub" (Secondary).

---

## 4. Color & Element Mapping (TailwindCSS / CSS equivalents)

For the UI Builder, map components to these visual patterns:
- **Main Chassis Shadow:** `box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.8), inset 0 1px 1px rgba(255, 255, 255, 0.15)`
- **Crimson Glow:** `box-shadow: 0 0 15px rgba(177, 22, 35, 0.6)`
- **Tactile Click Wheel Disc:** Background `#1F1F1F`, Border `#2A2A2A`, Center Cap `#2A2A2A` with subtle radial gradient.
- **Typography pairing:** Heavy modern sans-serif headings (like Outfit or Inter Bold) paired with neutral, low-contrast sans-serif body text (Inter Regular, opacity 75%-90%) and technical monospaced accents.
