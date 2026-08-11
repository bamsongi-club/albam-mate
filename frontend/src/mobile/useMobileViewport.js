import { useEffect, useState } from 'react';

export const MOBILE_VIEWPORT_QUERY = '(max-width: 767px)';

function matchesMobileViewport() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia(MOBILE_VIEWPORT_QUERY).matches;
}

export function useMobileViewport() {
  const [isMobile, setIsMobile] = useState(matchesMobileViewport);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;

    const media = window.matchMedia(MOBILE_VIEWPORT_QUERY);
    const update = () => setIsMobile(media.matches);
    update();
    media.addEventListener?.('change', update);
    return () => media.removeEventListener?.('change', update);
  }, []);

  return isMobile;
}
