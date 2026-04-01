import { createContext, useContext, useState, useEffect } from 'react';

const DEFAULT_BRANDING = {
  orgName:      'DecisionMesh',
  primaryColor: '#2563eb',
  logoUrl:      null,
  logoInitial:  'D',
  favicon:      null,
};

const BrandingContext = createContext(null);

function hexToHsl(hex) {
  let r = parseInt(hex.slice(1, 3), 16) / 255;
  let g = parseInt(hex.slice(3, 5), 16) / 255;
  let b = parseInt(hex.slice(5, 7), 16) / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  let h, s, l = (max + min) / 2;
  if (max === min) { h = s = 0; }
  else {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r: h = ((g - b) / d + (g < b ? 6 : 0)) / 6; break;
      case g: h = ((b - r) / d + 2) / 6; break;
      case b: h = ((r - g) / d + 4) / 6; break;
    }
  }
  return [Math.round(h * 360), Math.round(s * 100), Math.round(l * 100)];
}

function applyBrandingToDOM(branding) {
  const root = document.documentElement;
  const [h, s, l] = hexToHsl(branding.primaryColor || DEFAULT_BRANDING.primaryColor);

  // Set CSS variables used throughout the app
  root.style.setProperty('--brand-h',       h);
  root.style.setProperty('--brand-s',       `${s}%`);
  root.style.setProperty('--brand-l',       `${l}%`);
  root.style.setProperty('--brand-primary', branding.primaryColor);
  root.style.setProperty('--brand-light',   `hsl(${h}, ${s}%, ${Math.min(l + 40, 95)}%)`);
  root.style.setProperty('--brand-dark',    `hsl(${h}, ${s}%, ${Math.max(l - 10, 10)}%)`);
  root.style.setProperty('--brand-text',    `hsl(${h}, ${Math.min(s + 10, 100)}%, ${Math.max(l - 20, 15)}%)`);

  // Update favicon if provided
  if (branding.favicon) {
    let link = document.querySelector("link[rel~='icon']");
    if (!link) { link = document.createElement('link'); link.rel = 'icon'; document.head.appendChild(link); }
    link.href = branding.favicon;
  }

  // Update page title
  if (branding.orgName) {
    document.title = `${branding.orgName} — AI Control Plane`;
  }
}

export function BrandingProvider({ keycloak, children }) {
  const [branding, setBranding] = useState(DEFAULT_BRANDING);
  const [loading,  setLoading]  = useState(true);

  useEffect(() => {
    // Apply defaults immediately so UI renders branded from the start
    applyBrandingToDOM(DEFAULT_BRANDING);

    if (!keycloak?.authenticated) { setLoading(false); return; }

    fetch('http://localhost:8080/api/org/branding', {
      headers: { Authorization: `Bearer ${keycloak.token}` },
    })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (data) {
          const merged = { ...DEFAULT_BRANDING, ...data };
          setBranding(merged);
          applyBrandingToDOM(merged);
        }
      })
      .catch(() => { /* keep defaults */ })
      .finally(() => setLoading(false));
  }, [keycloak?.authenticated]);

  function updateBranding(updates) {
    const merged = { ...branding, ...updates };
    setBranding(merged);
    applyBrandingToDOM(merged);
  }

  return (
    <BrandingContext.Provider value={{ branding, updateBranding, loading }}>
      {children}
    </BrandingContext.Provider>
  );
}

export function useBranding() {
  const ctx = useContext(BrandingContext);
  if (!ctx) throw new Error('useBranding must be used inside BrandingProvider');
  return ctx;
}
