fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let path = args
        .next()
        .ok_or("Usage: optimize measurement.csv [bands=10]")?;
    let bands = args.next().map_or(Ok(10), |v| v.parse::<usize>())?;
    let points = autoeq::parse_csv(&std::fs::read_to_string(path)?)?;
    print!(
        "{}",
        autoeq::optimize(&points, bands, 48000)?.settings_text()
    );
    Ok(())
}
