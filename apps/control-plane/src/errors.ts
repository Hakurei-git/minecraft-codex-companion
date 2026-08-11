export class ControlError extends Error {
  readonly code: string;
  readonly statusCode: number;
  readonly retryable: boolean;
  readonly suggestedRecovery: string | undefined;

  constructor(options: {
    code: string;
    message: string;
    statusCode?: number;
    retryable?: boolean;
    suggestedRecovery?: string;
  }) {
    super(options.message);
    this.name = "ControlError";
    this.code = options.code;
    this.statusCode = options.statusCode ?? 400;
    this.retryable = options.retryable ?? false;
    this.suggestedRecovery = options.suggestedRecovery;
  }
}
