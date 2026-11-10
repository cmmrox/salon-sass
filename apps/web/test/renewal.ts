type Answers = Record<string, (body: unknown) => Response | Promise<Response>>;

const expired = () => Response.json({ code: 'invalid_session', renewable: true }, { status: 401 });

/**
 * The access credential lapsed five minutes after the last request (identity-web.ts sets it for 300 seconds) while the
 * refresh credential is still good: `action` and the principal answer 401 renewable until the browser renews, and
 * `refresh` renews. Returns how many renewals were made, read after the screen settles.
 */
export function expireAccessBefore(answers: Answers, action: string): () => number {
  let renewed = 0;
  const answer = answers[action];
  const principal = answers.principal ?? (() => Response.json({ kind: 'staff', permissions: [] }));
  if (!answer) throw new Error(`No answer for ${action}`);
  answers[action] = (body) => (renewed > 0 ? answer(body) : expired());
  answers.principal = (body) => (renewed > 0 ? principal(body) : expired());
  answers.refresh = () => {
    renewed++;
    return Response.json({ ok: true });
  };
  return () => renewed;
}
