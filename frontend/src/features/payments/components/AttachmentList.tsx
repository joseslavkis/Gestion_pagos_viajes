import { attachmentKeys, isImageAttachment } from "@/features/payments/lib/attachment-preview";

type Props = {
  receipt: { fileKey: string; fileKeys?: string[] };
  className?: string;
  imageClassName?: string;
};

export function AttachmentList({ receipt, className, imageClassName }: Props) {
  const keys = attachmentKeys(receipt);
  if (!keys.length) return null;

  return (
    <div className={className}>
      {keys.map((key, index) => (
        <div key={`${index}-${key}`}>
          {isImageAttachment(key) ? (
            <a href={key} target="_blank" rel="noreferrer" aria-label={`Abrir comprobante ${index + 1}`}>
              <img
                src={key}
                alt={`Comprobante ${index + 1}`}
                className={imageClassName}
                style={{ maxWidth: "100%", maxHeight: 200, objectFit: "contain" }}
              />
            </a>
          ) : (
            <a href={key} target="_blank" rel="noreferrer">
              Ver comprobante adjunto {index + 1}
            </a>
          )}
        </div>
      ))}
    </div>
  );
}
