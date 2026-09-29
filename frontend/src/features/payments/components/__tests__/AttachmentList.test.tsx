import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { AttachmentList } from "@/features/payments/components/AttachmentList";
import { attachmentKeys } from "@/features/payments/lib/attachment-preview";

describe("AttachmentList", () => {
  it("renders no attachments for an empty receipt", () => {
    const { container } = render(<AttachmentList receipt={{ fileKey: "", fileKeys: [] }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("falls back to an older singular receipt", () => {
    render(<AttachmentList receipt={{ fileKey: "https://example.test/old.pdf" }} />);
    expect(screen.getByRole("link", { name: /comprobante adjunto 1/i })).toHaveAttribute(
      "href",
      "https://example.test/old.pdf",
    );
  });

  it("does not show a legacy key twice alongside modern keys", () => {
    expect(attachmentKeys({ fileKey: "legacy", fileKeys: ["first", "second"] })).toEqual(["first", "second"]);
  });

  it("renders image thumbnails and individually linked PDFs in order", () => {
    render(
      <AttachmentList
        receipt={{
          fileKey: "one",
          fileKeys: ["https://example.test/a.webp?token=1", "https://example.test/b.pdf", "data:image/png;base64,abcd"],
        }}
      />,
    );
    expect(screen.getByAltText("Comprobante 1")).toHaveAttribute("src", "https://example.test/a.webp?token=1");
    expect(screen.getByRole("link", { name: /comprobante adjunto 2/i })).toHaveAttribute(
      "href",
      "https://example.test/b.pdf",
    );
    expect(screen.getByAltText("Comprobante 3")).toBeInTheDocument();
  });
});
