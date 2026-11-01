/**
 * The contrast a person sees between an element's text and what is behind it, as Chromium draws them: the
 * text colour and every background fill up the tree composited with their alpha and each element's opacity.
 * axe-core reports text over translucent fills and opacity as "incomplete" rather than as a violation, so a
 * screen that draws such text measures it with this (QA's F04-S03 dry run: the calendar's today, 3.65:1).
 */
const probe = () => {
  const canvas = document.createElement('canvas');
  canvas.width = 1;
  canvas.height = 1;
  return canvas.getContext('2d', { willReadFrequently: true })!;
};

type Rgba = [number, number, number, number];

/** Any CSS colour Chromium computes (rgb, oklch, color-mix) as straight RGBA 0-255 with alpha 0-1. */
function rgba(colour: string): Rgba {
  const context = probe();
  context.clearRect(0, 0, 1, 1);
  context.fillStyle = colour;
  context.fillRect(0, 0, 1, 1);
  const [r, g, b, a] = context.getImageData(0, 0, 1, 1).data;
  return [r!, g!, b!, a! / 255];
}

const over = (top: Rgba, bottom: Rgba): Rgba => {
  const alpha = top[3] + bottom[3] * (1 - top[3]);
  if (alpha === 0) return [0, 0, 0, 0];
  const mix = (i: number) => (top[i]! * top[3] + bottom[i]! * bottom[3] * (1 - top[3])) / alpha;
  return [mix(0), mix(1), mix(2), alpha];
};

/** The product of the opacities from the element up. */
function opacity(element: Element): number {
  let total = 1;
  for (let node: Element | null = element; node; node = node.parentElement) {
    total *= Number(getComputedStyle(node).opacity);
  }
  return total;
}

/** The colour behind the element's text: every fill from the page up to the element, composited. */
function background(element: Element): Rgba {
  const chain: Element[] = [];
  for (let node: Element | null = element; node; node = node.parentElement) chain.unshift(node);
  let colour: Rgba = [255, 255, 255, 1];
  for (const node of chain) {
    const fill = rgba(getComputedStyle(node).backgroundColor);
    colour = over([fill[0], fill[1], fill[2], fill[3] * opacity(node)], colour);
  }
  return colour;
}

const luminance = ([r, g, b]: Rgba) => {
  const channel = (value: number) => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
};

/** The WCAG contrast ratio of the element's drawn text against what is drawn behind it. */
export function drawnContrast(element: Element): number {
  const behind = background(element);
  const text = rgba(getComputedStyle(element).color);
  const drawn = over([text[0], text[1], text[2], text[3] * opacity(element)], behind);
  const [light, dark] = [luminance(drawn), luminance(behind)].sort((a, b) => b - a) as [number, number];
  return (light + 0.05) / (dark + 0.05);
}
