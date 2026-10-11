import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { messages, renderWithMessages } from '@/test/render';

import { StatusBody, StatusPanel } from './Status';

describe('StatusPanel', () => {
  it('shows the heading and whatever state is inside it', () => {
    renderWithMessages(
      <StatusPanel>
        <StatusBody state={{ kind: 'loading' }} retryHref="/" />
      </StatusPanel>,
    );

    const panel = screen.getByTestId('status-panel');
    expect(within(panel).getByRole('heading', { level: 1, name: messages.status.heading })).toBeVisible();
    expect(within(panel).getByTestId('status-loading')).toBeInTheDocument();
  });
});

describe('StatusBody', () => {
  it('loading: announces that it is checking and shows neither a result nor an error', () => {
    renderWithMessages(<StatusBody state={{ kind: 'loading' }} retryHref="/" />);

    expect(screen.getByRole('status')).toHaveTextContent(messages.status.loading);
    expect(screen.getByTestId('status-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('status-loaded')).not.toBeInTheDocument();
    expect(screen.queryByTestId('status-unavailable')).not.toBeInTheDocument();
  });

  it("loaded: shows the API's service name and version", () => {
    renderWithMessages(<StatusBody state={{ kind: 'loaded', service: 'salon-api', version: '1.2.3' }} retryHref="/" />);

    expect(screen.getByTestId('status-api-service')).toHaveTextContent('salon-api');
    expect(screen.getByTestId('status-api-version')).toHaveTextContent('1.2.3');
    expect(screen.getByTestId('status-summary')).toHaveTextContent(messages.status.loaded.summary);
    expect(screen.getByText(messages.status.loaded.service)).toBeVisible();
    expect(screen.getByText(messages.status.loaded.version)).toBeVisible();
    expect(screen.queryByTestId('status-loading')).not.toBeInTheDocument();
    expect(screen.queryByTestId('status-unavailable')).not.toBeInTheDocument();
  });

  it('unavailable: says so in plain words, offers a retry and shows no result', () => {
    renderWithMessages(<StatusBody state={{ kind: 'unavailable' }} retryHref="/again" />);

    const unavailable = screen.getByRole('alert');
    expect(unavailable).toHaveAttribute('data-testid', 'status-unavailable');
    expect(within(unavailable).getByRole('heading', { name: messages.status.unavailable.title })).toBeVisible();
    expect(unavailable).toHaveTextContent(messages.status.unavailable.body);
    const retry = within(unavailable).getByRole('link', { name: messages.status.unavailable.retry });
    expect(retry).toHaveAttribute('href', '/again');
    expect(retry).toHaveAttribute('data-testid', 'status-retry');
    expect(screen.queryByTestId('status-api-service')).not.toBeInTheDocument();
    expect(screen.queryByTestId('status-api-version')).not.toBeInTheDocument();
    expect(screen.queryByTestId('status-loading')).not.toBeInTheDocument();
  });

  it('shows a long version without dropping any of it', () => {
    const version = '0.1.0-SNAPSHOT+build.20261012.0123456789abcdef0123456789abcdef01234567';
    renderWithMessages(<StatusBody state={{ kind: 'loaded', service: 'salon-api', version }} retryHref="/" />);

    expect(screen.getByTestId('status-api-version')).toHaveTextContent(version);
  });
});
