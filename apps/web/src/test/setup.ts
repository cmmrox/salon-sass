import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

import { installMatchMedia, setViewportWidth } from './viewport';

// jsdom has no matchMedia; the Sidebar asks it whether the viewport is below the rail breakpoint.
if (typeof window !== 'undefined') installMatchMedia();

afterEach(() => {
  cleanup();
  if (typeof window !== 'undefined') setViewportWidth(1024);
});
