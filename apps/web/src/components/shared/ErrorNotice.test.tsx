import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { ErrorNotice } from './ErrorNotice';

// Web M3: a page that fails while rendering shows this, translated, instead of a blank or the framework's crash screen.
describe('the error page', () => {
  it('says what happened in plain words, never the error, and offers to try again or go to the start', () => {
    const retry = vi.fn();
    renderWithMessages(<ErrorNotice retry={retry} />);
    expect(screen.getByTestId('error-page-title')).toHaveTextContent('Something went wrong');
    expect(screen.getByTestId('error-page-body')).toHaveTextContent('Nothing you saved is lost');
    expect(screen.getByTestId('error-page-home')).toHaveAttribute('href', '/');
    fireEvent.click(screen.getByTestId('error-page-retry'));
    expect(retry).toHaveBeenCalledOnce();
  });
});
