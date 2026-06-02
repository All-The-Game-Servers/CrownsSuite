// PageHeader renders the editorial-style top of each view: roman numeral,
// display serif title, subtitle, and a hairline rule with a diagonal
// etched accent on the right.

interface Props {
  num: string
  title: string
  subtitle?: string
  right?: React.ReactNode
}

export function PageHeader({ num, title, subtitle, right }: Props) {
  return (
    <header className="mb-10 enter">
      <div className="flex items-end justify-between gap-6">
        <div>
          <div className="section-number mb-2">
            § {num}
          </div>
          <h1 className="section-title leading-none">{title}</h1>
          {subtitle && (
            <p className="section-sub mt-3 max-w-2xl">{subtitle}</p>
          )}
        </div>
        {right && <div className="flex items-center gap-2">{right}</div>}
      </div>
      <div className="mt-6 flex items-center gap-4">
        <div className="h-px flex-1 bg-iron" />
        <div className="rule-diag h-3 w-16" />
      </div>
    </header>
  )
}
