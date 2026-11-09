use std::{env, fs};

fn main() {
    let path = env::args().nth(1).expect("usage: inspect_sofa file.sofa");
    let bytes = fs::read(path).expect("read SOFA");
    let parsed = sofar::hdf::parse_with_children(&bytes).expect("parse HDF");
    for entry in &parsed.root.child_directories {
        match parsed.get_child(&entry.name) {
            Some(Ok(object)) => println!(
                "{}: {:?}, {} bytes, {:?}",
                entry.name,
                object.ds.dimension_size,
                object.data.len(),
                object.parsed_attributes
            ),
            Some(Err(error)) => println!("{}: ERROR {error:?}", entry.name),
            None => (),
        }
    }
    let sofa = sofar::reader::OpenOptions::new()
        .normalized(false)
        .sample_rate(44100.0)
        .open_data(&bytes)
        .expect("open SOFA");
    println!(
        "rate={} taps={} measurements={} ir={:?}",
        sofa.sample_rate(),
        sofa.filter_len(),
        sofa.num_measurements(),
        &sofa.hrtf().data_ir.values[..16.min(sofa.hrtf().data_ir.values.len())]
    );
    let mut filter = sofar::reader::Filter::new(sofa.filter_len());
    for (x, y) in [(1.0, 0.0), (0.8660254, 0.5), (0.0, 1.0)] {
        sofa.filter(x, y, 0.0, &mut filter);
        println!("{x},{y}: {:?} {:?}", filter.left, filter.right);
    }
}
